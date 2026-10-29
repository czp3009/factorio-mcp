#include "chat_read.h"
#include "linux_ipc.h"
#include "memory_read.h"
#include <algorithm>
#include <cerrno>
#include <cstring>
#include <unordered_set>
#include <sys/syscall.h>
#include <unistd.h>

namespace {
bool disjoint(uint32_t first, uint32_t width, uint32_t second, uint32_t otherWidth) {
    return uint64_t(first) + width <= second || uint64_t(second) + otherWidth <= first;
}

bool valid(const FmLinuxChatReadLayout &l) {
    if (l.consoleSize < 8 || l.consoleSize > 4096 || !fm::member(l.consolePlayer, 8, l.consoleSize) ||
        l.nodeSize < 24 || l.nodeSize > 4096 || !l.value || l.value >= l.nodeSize || !fm::member(l.next, 8, l.value) ||
        !fm::member(l.previous, 8, l.value) || !disjoint(l.next, 8, l.previous, 8) || !l.textSize ||
        l.textSize > 4096 || !l.stringSize || l.stringSize > 256 || !fm::member(l.cached, l.stringSize, l.textSize) ||
        !fm::member(l.stringData, 8, l.stringSize) || !fm::member(l.stringLength, 8, l.stringSize) ||
        !disjoint(l.stringData, 8, l.stringLength, 8) || (l.indexWidth != 1 && l.indexWidth != 2))
        return false;
    const auto payload = l.nodeSize - l.value;
    if (!fm::member(l.tick, 8, payload) || !fm::member(l.playerIndex, l.indexWidth, payload) ||
        !fm::member(l.text, l.textSize, payload) || !disjoint(l.tick, 8, l.playerIndex, l.indexWidth) ||
        !disjoint(l.tick, 8, l.text, l.textSize) || !disjoint(l.playerIndex, l.indexWidth, l.text, l.textSize) ||
        !fm::member(l.playerName, l.stringSize, payload) || !disjoint(l.playerName, l.stringSize, l.tick, 8) ||
        !disjoint(l.playerName, l.stringSize, l.playerIndex, l.indexWidth) ||
        !disjoint(l.playerName, l.stringSize, l.text, l.textSize))
        return false;
    for (unsigned i = 0; i < 2; ++i) {
        if (!fm::member(l.sentinel[i], l.value, l.consoleSize) || !fm::member(l.count[i], 8, l.consoleSize) ||
            !disjoint(l.sentinel[i], l.value, l.count[i], 8) || !disjoint(l.sentinel[i], l.value, l.consolePlayer, 8) ||
            !disjoint(l.count[i], 8, l.consolePlayer, 8))
            return false;
        for (unsigned j = 0; j < i; ++j)
            if (!disjoint(l.sentinel[i], l.value, l.sentinel[j], l.value) || !disjoint(l.count[i], 8, l.count[j], 8) ||
                !disjoint(l.count[i], 8, l.sentinel[j], l.value) || !disjoint(l.count[j], 8, l.sentinel[i], l.value))
                return false;
    }
    return true;
}

int copyString(uintptr_t object, const FmLinuxChatReadLayout &l, FmLinuxObservationBuffer &output, uint32_t &written, bool &truncated, ChatSnapshot &snapshot) {
    uintptr_t data;
    uint64_t size;
    if (!fm::addressRange(object, l.stringSize) || !fm::read(object + l.stringData, data) ||
        !fm::read(object + l.stringLength, size) || !data)
        return EFAULT;
    if (size && !fm::addressRange(data, size))
        return EFAULT;
    auto count = static_cast<size_t>(std::min(size, uint64_t(FM_LINUX_CHAT_RECORD_BYTES)));
    truncated = count < size;
    auto &copied = snapshot.strings.emplace_back(count + (truncated ? 1 : 0));
    if (!copied.empty() && !fm::readBytes(data, copied.data(), copied.size()))
        return EFAULT;
    if (truncated)
        while (count && (static_cast<unsigned char>(copied[count]) & 0xc0) == 0x80)
            --count;
    copied.resize(count);
    output = observationBuffer(copied);
    written = static_cast<uint32_t>(count);
    return 0;
}
} // namespace

int ChatReadCommand::record(int error) {
    if (error && !failure_)
        failure_ = error;
    return failure_;
}

int ChatReadCommand::cleanup() {
    if (owned_ && !destroyEntered_) {
        destroyEntered_ = true;
        try {
            destroy_(string_);
            owned_ = false;
        } catch (...) {
            record(EFAULT);
        }
    }
    finished_ = !owned_;
    return failure_;
}

int ChatReadCommand::start(const ChatReadConfig &config, uintptr_t console, uintptr_t player, const uint32_t *cancel,
                           ChatSnapshot &output) {
    if (syscall(SYS_gettid) != getpid())
        return EPERM;
    if (started_)
        return EALREADY;
    started_ = true;
    output.count = 0;
    output.consoleIdentity = 0;
    output.totals[0] = output.totals[1] = 0;
    const auto &l = config.layout;
    if (!cancel || !config.raw || !config.destroyString || !valid(l) || !player ||
        !fm::addressRange(console, l.consoleSize)) {
        finished_ = true;
        return record(EINVAL);
    }
    busy_ = true;
    destroy_ = config.destroyString;
    auto observe = [&]() -> int {
        if (fm_ipc_load(cancel))
            return ECANCELED;
        uintptr_t owner;
        if (!fm::read(console + l.consolePlayer, owner))
            return EFAULT;
        if (owner != player)
            return ESTALE;
        output.consoleIdentity = console;
        std::unordered_set<uintptr_t> visited;
        output.records.clear();
        output.strings.clear();
        for (unsigned stream = 0; stream < 2; ++stream) {
            const auto head = console + l.sentinel[stream];
            uintptr_t node, previous = head;
            uint64_t total;
            if (!fm::read(head + l.next, node) || !fm::read(console + l.count[stream], total))
                return EFAULT;
            if (total > INT32_MAX - output.records.size())
                return EOVERFLOW;
            output.totals[stream] = total;
            for (uint64_t i = 0; i < total; ++i) {
                if (fm_ipc_load(cancel))
                    return ECANCELED;
                if (!fm::addressRange(node, l.nodeSize) || node == head)
                    return ESTALE;
                if (!visited.insert(node).second)
                    return ESTALE;
                uintptr_t back, next, reciprocal;
                if (!fm::read(node + l.previous, back) || !fm::read(node + l.next, next) ||
                    !fm::addressRange(next, l.value) || !fm::read(next + l.previous, reciprocal))
                    return EFAULT;
                if (back != previous || reciprocal != node)
                    return ESTALE;
                output.records.emplace_back();
                auto &row = output.records.back();
                row = {};
                row.identity = node;
                row.stream = stream;
                const auto item = node + l.value;
                if (!fm::read(item + l.tick, row.tick))
                    return EFAULT;
                if (l.indexWidth == 1) {
                    uint8_t index;
                    if (!fm::read(item + l.playerIndex, index))
                        return EFAULT;
                    row.playerIndex = index;
                } else {
                    uint16_t index;
                    if (!fm::read(item + l.playerIndex, index))
                        return EFAULT;
                    row.playerIndex = index;
                }
                bool truncated;
                if (const auto error = copyString(item + l.text + l.cached, l, row.text, row.textSize, truncated, output))
                    return error;
                row.truncated = truncated ? 1 : 0;
                if (const auto error =
                        copyString(item + l.playerName, l, row.playerName, row.playerNameSize, truncated, output))
                    return error;
                if (truncated)
                    row.truncated |= 4;
                std::memset(string_, 0, sizeof(string_));
                config.raw(string_, reinterpret_cast<const void *>(item + l.text));
                owned_ = true;
                destroyEntered_ = false;
                const int copied = copyString(reinterpret_cast<uintptr_t>(string_), l, row.raw, row.rawSize, truncated, output);
                if (truncated)
                    row.truncated |= 2;
                record(copied);
                cleanup();
                if (failure_)
                    return failure_;
                if (fm_ipc_load(cancel))
                    return ECANCELED;
                ++output.count;
                previous = node;
                node = next;
            }
            {
                uintptr_t tail;
                if (!fm::read(head + l.previous, tail))
                    return EFAULT;
                if (node != head || tail != previous)
                    return ESTALE;
            }
        }
        return 0;
    };
    try {
        record(observe());
    } catch (const std::bad_alloc &) {
        record(ENOMEM);
    } catch (...) {
        record(EFAULT);
    }
    cleanup();
    busy_ = false;
    if (failure_)
        output.count = 0;
    return failure_;
}

int ChatReadCommand::finish() {
    if (syscall(SYS_gettid) != getpid())
        return EPERM;
    if (busy_)
        return EBUSY;
    if (!started_)
        return EINVAL;
    return cleanup();
}
