#include "keyboard_event.h"
#include <array>
#include <cassert>
#include <cerrno>
#include <cstring>
#include <limits>
#include <stdexcept>

static uint32_t ticks;
static unsigned clockCalls;
static bool failClock;

static uint32_t readTicks() {
    ++clockCalls;
    if (failClock)
        throw std::runtime_error("clock fixture");
    return ticks;
}

template <class T> static T read(const unsigned char *bytes, unsigned offset) {
    T value;
    std::memcpy(&value, bytes + offset, sizeof(value));
    return value;
}

int main() {
    FmLinuxEventClock clock{reinterpret_cast<uint64_t>(&readTicks), 2000};
    double time = -1;
    for (uint32_t now : {0u, 123456u, 0xffffffffu, 0u}) {
        ticks = now;
        const auto before = clockCalls;
        assert(readEventClock(clock, time) == 0 && time == now / 2000.0 && clockCalls == before + 1);
    }
    failClock = true;
    time = 77;
    assert(readEventClock(clock, time) == EIO && time == 77);
    failClock = false;
    clock.divisor = 0;
    assert(readEventClock(clock, time) == EINVAL && time == 77);
    clock.divisor = std::numeric_limits<double>::denorm_min();
    ticks = 1;
    assert(readEventClock(clock, time) == ERANGE && time == 77);
    for (uint32_t padding : {1u, 23u, 57u}) {
        FmLinuxKeyboardEventLayout layout{};
        layout.extent = padding + 48;
        layout.type = padding;
        layout.time = padding + 8;
        layout.code = padding + 24;
        layout.emptyType = 77;
        layout.press = 19;
        layout.release = 39;
        for (unsigned byte = padding + 28; byte < padding + 36; ++byte) {
            layout.initialized[byte] = 1;
            layout.defaults[byte] = static_cast<uint8_t>(byte);
        }
        assert(validKeyboardEventLayout(layout));
        for (const bool down : {false, true}) {
            KeyboardEventStorage local;
            assert(!local.data() && local.size() == 0);
            assert(local.prepare(layout, 0, down, 1) == EINVAL && !local.data());
            assert(local.prepare(layout, 0xffffffff, down, 1) == EINVAL && !local.data());
            assert(local.prepare(layout, 211, down, -1) == ERANGE && !local.data());
            assert(local.prepare(layout, 211, down, std::numeric_limits<double>::quiet_NaN()) == ERANGE);
            auto invalid = layout;
            invalid.extent = FM_LINUX_EVENT_BYTES + 1;
            assert(local.prepare(invalid, 211, down, 1) == EINVAL && !local.data());
            assert(local.prepare(layout, 211, down, 123.456) == 0);
            const auto *bytes = static_cast<const unsigned char *>(local.data());
            assert(reinterpret_cast<uintptr_t>(bytes) % 16 == 0 && local.size() == layout.extent);
            assert(read<uint32_t>(bytes, layout.type) == (down ? layout.press : layout.release));
            assert(read<uint32_t>(bytes, layout.code) == 211 && read<double>(bytes, layout.time) == 123.456);
            for (unsigned byte = 0; byte < layout.extent; ++byte)
                if (layout.initialized[byte])
                    assert(bytes[byte] == layout.defaults[byte]);
            std::array<unsigned char, FM_LINUX_EVENT_BYTES> before;
            std::memcpy(before.data(), bytes, local.size());
            assert(local.prepare(layout, 212, !down, 124) == EALREADY);
            assert(local.data() == bytes && std::memcmp(bytes, before.data(), local.size()) == 0);
        }
        std::array<unsigned char, FM_LINUX_EVENT_BYTES + 32> storage;
        auto *event = storage.data() + 16;
        auto reset = [&] {
            storage.fill(0xa5);
            std::memcpy(event + layout.type, &layout.emptyType, 4);
            std::memset(event + layout.time, 0, 8);
        };
        for (bool down : {false, true}) {
            reset();
            const auto before = storage;
            assert(writeKeyboardEvent(layout, event, layout.extent, 211, down, 123.456) == 0);
            assert(read<uint32_t>(event, layout.type) == (down ? layout.press : layout.release));
            assert(read<uint32_t>(event, layout.code) == 211);
            assert(read<double>(event, layout.time) == 123.456);
            for (size_t byte = 0; byte < storage.size(); ++byte) {
                if (byte < 16 || byte >= 16 + layout.extent) {
                    assert(storage[byte] == before[byte]);
                    continue;
                }
                const auto offset = byte - 16;
                if (layout.initialized[offset])
                    assert(event[offset] == layout.defaults[offset]);
                else if (!(offset >= layout.type && offset < layout.type + 4) &&
                         !(offset >= layout.time && offset < layout.time + 8) &&
                         !(offset >= layout.code && offset < layout.code + 4))
                    assert(storage[byte] == before[byte]);
            }
            const auto written = storage;
            assert(writeKeyboardEvent(layout, event, layout.extent, 211, down, 123.457) == ESTALE);
            assert(storage == written);
        }
        auto reject = [&](const FmLinuxKeyboardEventLayout &changed, size_t capacity, double timestamp, int error) {
            reset();
            const auto before = storage;
            assert(writeKeyboardEvent(changed, event, capacity, 211, true, timestamp) == error);
            assert(storage == before);
        };
        reject(layout, layout.extent - 1, 0, EINVAL);
        reject(layout, layout.extent, -1, ERANGE);
        reject(layout, layout.extent, std::numeric_limits<double>::infinity(), ERANGE);
        reject(layout, layout.extent, std::numeric_limits<double>::quiet_NaN(), ERANGE);
        for (unsigned mutation = 0; mutation < 7; ++mutation) {
            auto invalid = layout;
            switch (mutation) {
                case 0: invalid.extent = FM_LINUX_EVENT_BYTES + 1; break;
                case 1: invalid.code = layout.type; break;
                case 2: invalid.release = layout.press; break;
                case 3: invalid.press = layout.emptyType; break;
                case 4: invalid.initialized[layout.type] = 1; break;
                case 5: invalid.initialized[layout.extent] = 1; break;
                case 6: invalid.initialized[layout.extent - 1] = 2; break;
            }
            reject(invalid, layout.extent, 0, EINVAL);
        }
        assert(writeKeyboardEvent(layout, nullptr, layout.extent, 211, true, 0) == EINVAL);
        reset();
        event[layout.time] = 1;
        const auto before = storage;
        assert(writeKeyboardEvent(layout, event, layout.extent, 211, true, 0) == ESTALE);
        assert(storage == before);
    }
}
