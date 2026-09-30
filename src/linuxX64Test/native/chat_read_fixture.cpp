#include "chat_read.h"
#include <cassert>
#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {
struct String { const char *data; uint64_t size; char local[16]; };
struct Text { String cached; const char *raw; size_t size; };
struct Item { uint64_t tick; Text text; uint16_t index; };
struct Links { uintptr_t next, previous; };
struct Node { Links links; Item item; };
struct List { Links head; uint64_t count; };
struct Console { void *player; List lists[2]; };
static FmLinuxChatSnapshot result;
unsigned formatted, destroyed;
bool failRaw, failDestroy;
uint32_t *cancelDuringRaw;
ChatReadCommand *reentrant;

void raw(void *output, const void *input) {
    ++formatted;
    if (reentrant)
        assert(reentrant->finish() == EBUSY);
    if (failRaw)
        throw std::runtime_error("fixture format failure");
    const auto &text = *static_cast<const Text *>(input);
    *static_cast<String *>(output) = String{text.raw, text.size, {}};
    if (cancelDuringRaw)
        *cancelDuringRaw = 1;
}

void destroy(void *) {
    ++destroyed;
    if (failDestroy)
        throw std::runtime_error("fixture destruction failure");
}

ChatReadConfig config() {
    FmLinuxChatReadLayout l{};
    l.consoleSize = sizeof(Console);
    l.consolePlayer = offsetof(Console, player);
    for (unsigned i = 0; i < 2; ++i) {
        l.sentinel[i] = offsetof(Console, lists) + i * sizeof(List) + offsetof(List, head);
        l.count[i] = offsetof(Console, lists) + i * sizeof(List) + offsetof(List, count);
    }
    l.nodeSize = sizeof(Node);
    l.next = offsetof(Node, links) + offsetof(Links, next);
    l.previous = offsetof(Node, links) + offsetof(Links, previous);
    l.value = offsetof(Node, item);
    l.tick = offsetof(Item, tick);
    l.playerIndex = offsetof(Item, index);
    l.indexWidth = sizeof(Item::index);
    l.text = offsetof(Item, text);
    l.textSize = sizeof(Text);
    l.cached = offsetof(Text, cached);
    l.stringSize = sizeof(String);
    l.stringData = offsetof(String, data);
    l.stringLength = offsetof(String, size);
    return {l, raw, destroy};
}

struct Fixture {
    int player = 0;
    uint32_t cancel = 0;
    Console console{};
    Node nodes[2]{};
    std::string special = "[gps=1,2] [item=iron-plate] 中文 Ω";
    Fixture() {
        formatted = destroyed = 0;
        failRaw = failDestroy = false;
        cancelDuringRaw = nullptr;
        reentrant = nullptr;
        console.player = &player;
        for (unsigned i = 0; i < 2; ++i) {
            auto &head = console.lists[i].head;
            auto &node = nodes[i];
            head.next = head.previous = reinterpret_cast<uintptr_t>(&node);
            node.links.next = node.links.previous = reinterpret_cast<uintptr_t>(&head);
            console.lists[i].count = 1;
            node.item = {123 + i, {{special.data(), special.size(), {}}, special.data(), special.size()}, uint16_t(500 + i)};
        }
    }
    int start(ChatReadCommand &command, ChatReadConfig c = config()) {
        return command.start(c, reinterpret_cast<uintptr_t>(&console), reinterpret_cast<uintptr_t>(&player), &cancel, result);
    }
};
} // namespace

int main() {
    {
        Fixture f;
        ChatReadCommand command;
        reentrant = &command;
        assert(f.start(command) == 0 && command.finished());
        assert(result.count == 2 && result.totals[0] == 1 && result.totals[1] == 1);
        assert(formatted == 2 && destroyed == 2 && command.finish() == 0);
        for (unsigned i = 0; i < 2; ++i) {
            const auto &row = result.records[i];
            assert(row.tick == 123 + i && row.stream == i && row.playerIndex == 500 + i && !row.truncated);
            assert(row.textSize == f.special.size() && row.rawSize == f.special.size());
            assert(std::string(row.text, row.textSize) == f.special && std::string(row.raw, row.rawSize) == f.special);
        }
        assert(f.start(command) == EALREADY);
    }
    {
        Fixture f;
        std::string value(4094, 'x');
        value += "Ω";
        f.nodes[0].item.text = {{value.data(), value.size(), {}}, "a\0b", 3};
        ChatReadCommand command;
        assert(f.start(command) == 0);
        assert(result.records[0].textSize == 4094 && result.records[0].truncated == 1);
        assert(result.records[0].rawSize == 3 && std::memcmp(result.records[0].raw, "a\0b", 3) == 0);
    }
    {
        Fixture f;
        for (auto &list : f.console.lists) {
            list.head.next = list.head.previous = reinterpret_cast<uintptr_t>(&list.head);
            list.count = 0;
        }
        ChatReadCommand command;
        assert(f.start(command) == 0 && result.count == 0 && formatted == 0 && destroyed == 0);
    }
    {
        Fixture f;
        std::vector<Node> nodes(FM_LINUX_CHAT_RECORDS / 2 + 1, f.nodes[0]);
        auto &head = f.console.lists[0].head;
        head.next = reinterpret_cast<uintptr_t>(&nodes.front());
        head.previous = reinterpret_cast<uintptr_t>(&nodes.back());
        f.console.lists[0].count = nodes.size();
        for (size_t i = 0; i < nodes.size(); ++i) {
            nodes[i].links.previous = i ? reinterpret_cast<uintptr_t>(&nodes[i - 1]) : reinterpret_cast<uintptr_t>(&head);
            nodes[i].links.next = i + 1 < nodes.size() ? reinterpret_cast<uintptr_t>(&nodes[i + 1]) : reinterpret_cast<uintptr_t>(&head);
        }
        ChatReadCommand command;
        assert(f.start(command) == 0 && result.count == FM_LINUX_CHAT_RECORDS / 2 + 1);
        assert(result.totals[0] == nodes.size() && formatted == result.count && destroyed == result.count);
    }
    for (unsigned failure = 0; failure < 8; ++failure) {
        Fixture f;
        if (failure == 0) f.console.player = nullptr;
        if (failure == 1) f.nodes[0].links.previous = 0;
        if (failure == 2) f.nodes[0].links.next = 1;
        if (failure == 3) f.console.lists[0].count = 65537;
        if (failure == 4) f.console.lists[0].count = 2;
        if (failure == 5) f.nodes[0].item.text.cached.data = reinterpret_cast<const char *>(1);
        if (failure == 6) f.nodes[0].item.text.cached.size = 1024 * 1024 + 1;
        if (failure == 7) f.cancel = 1;
        ChatReadCommand command;
        assert(f.start(command) != 0 && command.finished() && result.count == 0);
        assert(formatted == destroyed);
    }
    {
        Fixture f;
        cancelDuringRaw = &f.cancel;
        ChatReadCommand command;
        assert(f.start(command) == ECANCELED && command.finished() && result.count == 0);
        assert(formatted == 1 && destroyed == 1);
    }
    {
        Fixture f;
        failRaw = true;
        ChatReadCommand command;
        assert(f.start(command) == EFAULT && command.finished() && result.count == 0);
        assert(formatted == 1 && destroyed == 0);
    }
    {
        Fixture f;
        failDestroy = true;
        ChatReadCommand command;
        assert(f.start(command) == EFAULT && !command.finished() && result.count == 0);
        assert(command.finish() == EFAULT && !command.finished() && destroyed == 1);
    }
    {
        Fixture f;
        auto c = config();
        c.layout.tick = c.layout.text;
        ChatReadCommand command;
        assert(f.start(command, c) == EINVAL && command.finished() && formatted == 0);
    }
    {
        Fixture f;
        ChatReadCommand command;
        std::thread worker([&] { assert(f.start(command) == EPERM); });
        worker.join();
        assert(f.start(command) == 0);
    }
}
