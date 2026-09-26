#include "detour.h"
#include "frontend_input.h"
#include <cassert>
#include <cstddef>
#include <memory>
#include <new>
#include <set>
#include <vector>
#include <cstdio>
#include <cstdlib>
#include <stdexcept>

static unsigned destroyed{};

__declspec(noinline) static void increment(volatile unsigned *value) {
    *value = *value + 1;
}

struct Keyboard {
    uint64_t padding;
    uint32_t scan;
};

struct EventFixture {
    uint64_t padding[2];
    double timestamp;
    int type;
    Keyboard keyboard;

    EventFixture(double time, int kind) : padding{}, timestamp(time), type(kind), keyboard{} {}

    ~EventFixture() {
        ++destroyed;
    }
};

struct OptionalFixture {
    uint64_t padding;

    union {
        char dummy;
        EventFixture event;
    };

    bool engaged;

    OptionalFixture() : padding(123), dummy{}, engaged(false) {}

    OptionalFixture(const OptionalFixture &) = delete;

    ~OptionalFixture() {
        if (engaged)
            event.~EventFixture();
    }
};

struct SourceFixture {
    volatile unsigned calls{};

    __declspec(noinline) OptionalFixture next() {
        volatile unsigned padding[8];
        padding[0] = 17;
        increment(&padding[0]);
        increment(&calls);
        return OptionalFixture();
    }
};

static Detour hook;
static Symbols symbols{};
static KeyGesture gesture;

static uint32_t ticks() {
    return 123450;
}

static void *construct(void *storage, double time, int type) {
    return new (storage) EventFixture(time, type);
}

static void *next(void *receiver, void *storage) {
    auto original = reinterpret_cast<void *(*)(void *, void *)>(hook.trampoline());
    void *result = original(receiver, storage);
    assert(result == storage);
    auto &optional = *static_cast<OptionalFixture *>(result);
    assert(!optional.engaged && optional.padding == 123);
    uint32_t key;
    bool down;
    if (gesture.next(key, down))
        writeKeyboardEvent(symbols, storage, key, down);
    return result;
}

static int run() {
    _set_error_mode(_OUT_TO_STDERR);
    _set_abort_behavior(0, _WRITE_ABORT_MSG | _CALL_REPORTFAULT);
    auto &layout = symbols.input;
    layout.supported = 1;
    layout.optionalValue = offsetof(OptionalFixture, event);
    layout.optionalEngaged = offsetof(OptionalFixture, engaged);
    layout.eventSize = sizeof(EventFixture);
    layout.keyboard = offsetof(EventFixture, keyboard);
    layout.scancode = offsetof(Keyboard, scan);
    layout.keyDown = 71;
    layout.keyUp = 98;
    symbols.address[SdlTicks] = reinterpret_cast<uintptr_t>(ticks);
    symbols.address[EventConstructor] = reinterpret_cast<uintptr_t>(construct);
    auto method = &SourceFixture::next;
    static_assert(sizeof(method) == sizeof(void *));
    void *address;
    memcpy(&address, &method, sizeof(address));
    hook.prepare(address, reinterpret_cast<void *>(next));
    Detour::change({&hook}, true);
    SourceFixture source;
    const uint32_t chord[] = {42, 56, 89};
    // Cancel before admission, after each down/up, and at the final acknowledgement boundary.
    for (unsigned cancelAt = 0; cancelAt <= 7; ++cancelAt) {
        gesture.begin(chord, 3);
        std::set<uint32_t> held;
        unsigned events = 0;
        for (unsigned step = 0; step < 8; ++step) {
            if (step == cancelAt)
                gesture.cancel();
            auto event = source.next();
            if (!event.engaged)
                break;
            ++events;
            assert(event.event.timestamp == 123.45);
            const auto key = event.event.keyboard.scan;
            if (event.event.type == int(layout.keyDown))
                assert(held.insert(key).second);
            else {
                assert(event.event.type == int(layout.keyUp));
                assert(held.erase(key) == 1);
            }
        }
        assert(!gesture.active() && held.empty());
        assert(events % 2 == 0);
    }
    assert(source.calls > 0 && destroyed > 0);
    Detour::change({&hook}, false);
    assert(!source.next().engaged);
    return 0;
}

int main() {
    try {
        return run();
    } catch (const std::exception &error) {
        fprintf(stderr, "%s\n", error.what());
        return 1;
    }
}
