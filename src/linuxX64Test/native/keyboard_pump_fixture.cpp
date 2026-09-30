#include "keyboard_pump.h"
#include <cassert>
#include <cerrno>
#include <cstddef>
#include <stdexcept>
#include <sys/mman.h>
#include <thread>

namespace {
struct Value {
    uint8_t held = 0, blocked = 0;
};
struct Record {
    int32_t code = 42;
    Value value;
    uint16_t padding = 0;
};
struct State {
    uint64_t buttons = 0;
    Record *begin = nullptr, *end = nullptr;
};
struct Global {
    State *state;
};
struct Event {
    uint32_t type = 0, code = 0;
    double time = 0;
};
struct Frame {
    uintptr_t outer = 0x2000;
    Event event;
};

bool failClock = false;

uint32_t ticks() {
    if (failClock)
        throw std::runtime_error("fixture clock failed");
    return 1234;
}

struct Fixture {
    Record record;
    State state{0, &record, &record + 1};
    State replacement;
    Global global{&state};
    Global *root = &global;
    uintptr_t table[1]{0x3000};
    uintptr_t *window = table;
    Frame frame;
    EventPump pump;
    KeyboardPumpConfig config{};
    unsigned calls = 0, downs = 0, ups = 0;
    bool throwAfterDelivery = false, omitEmpty = false, omitPoll = false, ignoreUp = false;
    bool replaceBeforePoll = false;
    KeyboardPumpKey *reentrant = nullptr;

    Fixture() {
        auto &site = config.site;
        site.table = site.entry = reinterpret_cast<uintptr_t>(table);
        site.original = table[0];
        site.caller = 0x1000;
        site.pumpCaller = frame.outer;
        site.eventFromFrame = offsetof(Frame, event);
        site.eventExtent = sizeof(Event);
        site.protection = PROT_READ;
        config.owner = {reinterpret_cast<uintptr_t>(&root), sizeof(Global), offsetof(Global, state), sizeof(State),
            offsetof(State, buttons), sizeof(Event), offsetof(Event, type), offsetof(Event, time),
            offsetof(Event, code), 3, 4, {1, 2, 3}, {2, 4, 8}};
        config.keys = {0, offsetof(State, begin), offsetof(State, end), sizeof(Record), offsetof(Record, code),
            offsetof(Record, value), sizeof(Value), offsetof(Value, held), offsetof(Value, blocked)};
        config.event.extent = sizeof(Event);
        config.event.type = offsetof(Event, type);
        config.event.time = offsetof(Event, time);
        config.event.code = offsetof(Event, code);
        config.event.press = 1;
        config.event.release = 2;
        config.clock = {reinterpret_cast<uintptr_t>(ticks), 1000};
    }

    int poll() {
        frame.event = {};
        return pump.intercept(&window, &frame.event, config.site.caller, reinterpret_cast<uintptr_t>(&frame));
    }

    static void run(void *context) {
        auto &self = *static_cast<Fixture *>(context);
        ++self.calls;
        if (self.reentrant)
            assert(self.reentrant->release() == EBUSY);
        if (self.omitPoll)
            return;
        if (self.replaceBeforePoll)
            self.global.state = &self.replacement;
        const int available = self.poll();
        assert(available == 0 || available == 1);
        if (available) {
            const auto &event = self.frame.event;
            assert(event.code == uint32_t(self.record.code) && event.time == 1.234);
            assert(event.type == 1 || event.type == 2);
            if (event.type == 1) {
                ++self.downs;
                self.record.value.held = 1;
            } else {
                ++self.ups;
                if (!self.ignoreUp)
                    self.record.value.held = 0;
            }
            if (self.throwAfterDelivery)
                throw std::runtime_error("native routing failed after input-state update");
            if (!self.omitEmpty)
                assert(self.poll() == 0);
        }
    }
};
} // namespace

int main() {
    {
        Fixture f;
        KeyboardPumpKey key(f.pump, f.config, Fixture::run, &f);
        f.reentrant = &key;
        assert(key.release() == EINVAL);
        assert(key.press(42) == 0 && key.owned() && f.downs == 1 && f.record.value.held);
        assert(key.press(42) == EALREADY && f.downs == 1);
        assert(key.release() == 0 && !key.owned() && f.ups == 1 && !f.record.value.held);
        assert(key.release() == 0 && f.ups == 1);
    }
    for (bool blocked : {false, true}) {
        Fixture f;
        f.record.value = {uint8_t(!blocked), uint8_t(blocked)};
        KeyboardPumpKey key(f.pump, f.config, Fixture::run, &f);
        assert(key.press(42) == EBUSY && !key.owned() && f.calls == 0);
        assert(key.release() == EBUSY && f.calls == 0);
    }
    {
        Fixture f;
        f.throwAfterDelivery = true;
        KeyboardPumpKey key(f.pump, f.config, Fixture::run, &f);
        assert(key.press(42) == EFAULT && key.owned() && key.pressProgress().delivered);
        f.throwAfterDelivery = false;
        assert(key.release() == EFAULT && !key.owned() && f.ups == 1);
    }
    // Cleared native state does not erase an uncertain route or authorize replaying the up.
    for (unsigned failure = 0; failure < 3; ++failure) {
        Fixture f;
        KeyboardPumpKey key(f.pump, f.config, Fixture::run, &f);
        assert(key.press(42) == 0);
        f.throwAfterDelivery = failure == 0;
        f.omitEmpty = failure == 1;
        f.ignoreUp = failure == 2;
        assert(key.release() != 0 && key.owned() && f.ups == 1);
        f.throwAfterDelivery = f.omitEmpty = f.ignoreUp = false;
        assert(key.release() != 0 && key.owned() && f.ups == 1);
        f.global.state = &f.replacement;
        assert(key.release() != 0 && key.owned() && f.ups == 1);
    }
    {
        Fixture f;
        KeyboardPumpKey key(f.pump, f.config, Fixture::run, &f);
        assert(key.press(42) == 0);
        f.omitPoll = true;
        assert(key.release() == EPROTO && key.owned() && f.calls == 2 && f.ups == 0);
        f.omitPoll = false;
        assert(key.release() == EPROTO && !key.owned() && f.calls == 3 && f.ups == 1);
    }
    {
        Fixture f;
        f.replaceBeforePoll = true;
        KeyboardPumpKey key(f.pump, f.config, Fixture::run, &f);
        assert(key.press(42) == ESTALE && !key.owned() && !key.pressProgress().delivered && f.downs == 0);
    }
    {
        Fixture f;
        KeyboardPumpKey key(f.pump, f.config, Fixture::run, &f);
        assert(key.press(42) == 0);
        failClock = true;
        assert(key.release() == EIO && key.owned() && !key.releaseProgress().delivered && f.ups == 0);
        failClock = false;
        assert(key.release() == EIO && !key.owned() && f.ups == 1);
    }
    {
        Fixture f;
        KeyboardPumpKey key(f.pump, f.config, Fixture::run, &f);
        assert(key.press(42) == 0);
        f.record.value.held = 2;
        assert(key.release() == EPROTO && key.owned() && f.calls == 1);
        f.record.value.held = 1;
        assert(key.release() == EPROTO && !key.owned() && f.ups == 1);
    }
    {
        Fixture f;
        KeyboardPumpKey key(f.pump, f.config, Fixture::run, &f);
        std::thread other([&] { assert(key.press(42) == EPERM); });
        other.join();
        assert(key.press(42) == 0);
        std::thread cleanup([&] { assert(key.release() == EPERM); });
        cleanup.join();
        assert(key.owned() && f.ups == 0);
        assert(key.release() == 0 && !key.owned());
    }
    for (unsigned invalid = 0; invalid < 4; ++invalid) {
        Fixture f;
        if (invalid == 0)
            ++f.config.site.eventExtent;
        if (invalid == 1)
            ++f.config.owner.eventTime;
        if (invalid == 2)
            f.config.event.code = f.config.event.type;
        KeyboardPumpKey key(f.pump, f.config, Fixture::run, &f);
        assert(key.press(invalid == 3 ? 0xffffffff : 42) == EINVAL && !key.owned() && f.calls == 0);
    }
}
