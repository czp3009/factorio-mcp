#include "pump_input.h"
#include <cassert>
#include <cerrno>
#include <cstddef>
#include <memory>
#include <stdexcept>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <thread>
#include <unistd.h>

namespace {
struct KeyValue {
    uint8_t held = 0, blocked = 0;
};

struct KeyRecord {
    int32_t code = 42;
    KeyValue value;
    uint16_t padding = 0;
};

struct State {
    uint32_t held = 0, padding = 0;
    int32_t x = -31, y = 71;
    uint8_t inWindow = 0;
    KeyRecord *begin = nullptr, *end = nullptr;
};

struct Global {
    State *state;
};

struct Event {
    uint32_t type = 0, code = 0;
    double time = 0;
    int32_t x = 0, y = 0, dx = 0, dy = 0, wheel = 0;
    uint32_t padding = 0;
};

struct Frame {
    Event event;
    uintptr_t outer = 0x2000;
};

bool failClock = false;

uint32_t ticks() {
    if (failClock)
        throw std::runtime_error("fixture clock");
    return 4512;
}

struct Fixture {
    KeyRecord record;
    State state, replacement;
    Global global{&state};
    Global *root = &global;
    uintptr_t table[1]{0x3000};
    uintptr_t *window = table;
    Frame frame;
    EventPump pump;
    PointerPumpConfig pointer{};
    KeyboardPumpConfig keyboard{};
    unsigned calls = 0, downs = 0, ups = 0, moves = 0, enters = 0, wheels = 0;
    int32_t lastX = 0, lastY = 0, lastWheel = 0;
    bool world = true, invalidateOnEnter = false, throwAfterDelivery = false, omitEmpty = false;
    bool omitPoll = false, ignoreUp = false, replaceBeforePoll = false;
    PointerPumpButton *reentrant = nullptr;
    PointerPump *guardReentrant = nullptr;

    Fixture() {
        state.begin = &record;
        state.end = &record + 1;
        auto &site = pointer.site;
        site.table = site.entry = reinterpret_cast<uintptr_t>(table);
        site.original = table[0];
        site.caller = 0x1000;
        site.pumpCaller = frame.outer;
        site.stackReturn = offsetof(Frame, outer);
        site.eventFromStack = offsetof(Frame, event);
        site.eventExtent = sizeof(Event);
        site.protection = PROT_READ;
        pointer.owner = {reinterpret_cast<uintptr_t>(&root),
                         sizeof(Global),
                         offsetof(Global, state),
                         sizeof(State),
                         offsetof(State, held),
                         sizeof(Event),
                         offsetof(Event, type),
                         offsetof(Event, time),
                         offsetof(Event, code),
                         11,
                         12,
                         {7, 13, 19},
                         {1, 4, 2}};
        pointer.state = {offsetof(State, x), offsetof(State, inWindow), {1, 2, 4, 8, 16}};
        auto &event = pointer.event;
        event.extent = sizeof(Event);
        event.type = offsetof(Event, type);
        event.time = offsetof(Event, time);
        const uint32_t codes[]{7, 19, 13, 23, 29};
        for (unsigned index = 0; index < FM_LINUX_POINTER_BUTTONS; ++index)
            event.codes[index] = codes[index];
        for (unsigned operation = 0; operation < FM_LINUX_POINTER_CASES; ++operation) {
            auto &item = event.cases[operation];
            item.kind = 11 + operation;
            item.x = item.y = item.code = item.wheel = item.wheelY = FM_LINUX_POINTER_NO_FIELD;
            if (operation != FM_LINUX_POINTER_ENTER) {
                item.x = offsetof(Event, x);
                item.y = offsetof(Event, y);
            }
            if (operation == FM_LINUX_POINTER_PRESS || operation == FM_LINUX_POINTER_RELEASE)
                item.code = offsetof(Event, code);
            if (operation == FM_LINUX_POINTER_WHEEL) {
                item.wheel = offsetof(Event, wheel);
                item.wheelY = offsetof(Event, dy);
            }
            for (unsigned byte = 0; byte < sizeof(Event); ++byte) {
                bool dynamic = byte >= event.time && byte - event.time < 8;
                for (auto at : {event.type, item.x, item.y, item.code, item.wheel, item.wheelY})
                    dynamic |= at != FM_LINUX_POINTER_NO_FIELD && byte >= at && byte - at < 4;
                item.initialized[byte] = !dynamic;
            }
        }
        pointer.clock = {reinterpret_cast<uintptr_t>(ticks), 1000};
        keyboard.site = pointer.site;
        keyboard.owner = pointer.owner;
        keyboard.keys = {0,
                         offsetof(State, begin),
                         offsetof(State, end),
                         sizeof(KeyRecord),
                         offsetof(KeyRecord, code),
                         offsetof(KeyRecord, value),
                         sizeof(KeyValue),
                         offsetof(KeyValue, held),
                         offsetof(KeyValue, blocked)};
        keyboard.event.extent = sizeof(Event);
        keyboard.event.type = offsetof(Event, type);
        keyboard.event.time = offsetof(Event, time);
        keyboard.event.code = offsetof(Event, code);
        keyboard.event.press = 61;
        keyboard.event.release = 62;
        keyboard.clock = pointer.clock;
        assert(validPointerPumpConfig(pointer));
    }

    static int guard(void *context) {
        auto &self = *static_cast<Fixture *>(context);
        if (self.guardReentrant) {
            EventPumpProgress progress;
            assert(self.guardReentrant->wheel(1, progress) == EBUSY);
        }
        return self.world ? 0 : ESTALE;
    }

    int poll() {
        frame.event = {};
        return pump.intercept(&window, &frame.event, pointer.site.caller, reinterpret_cast<uintptr_t>(&frame));
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
        const auto available = self.poll();
        assert(available == 0 || available == 1);
        if (!available)
            return;
        const auto &event = self.frame.event;
        assert(event.time == 4.512);
        self.lastX = event.x;
        self.lastY = event.y;
        if (event.type == 61 || event.type == 62) {
            assert(event.code == 42);
            if (event.type == 61) {
                ++self.downs;
                self.record.value.held = 1;
            } else {
                ++self.ups;
                if (!self.ignoreUp)
                    self.record.value.held = 0;
            }
        } else if (event.type == 11 || event.type == 12) {
            unsigned button = 0;
            while (button < 5 && self.pointer.event.codes[button] != event.code)
                ++button;
            assert(button < 5 && event.x == self.state.x && event.y == self.state.y);
            if (event.type == 11) {
                ++self.downs;
                self.state.held |= self.pointer.state.masks[button];
            } else {
                ++self.ups;
                if (!self.ignoreUp)
                    self.state.held &= ~self.pointer.state.masks[button];
            }
        } else if (event.type == 13) {
            ++self.moves;
            self.state.x = event.x;
            self.state.y = event.y;
        } else if (event.type == 14) {
            ++self.wheels;
            assert(event.x == self.state.x && event.y == self.state.y && event.wheel == event.dy);
            assert(event.wheel == -1 || event.wheel == 1);
            self.lastWheel = event.wheel;
        } else {
            assert(event.type == 15);
            ++self.enters;
            self.state.inWindow = 1;
            if (self.invalidateOnEnter)
                self.world = false;
        }
        if (self.throwAfterDelivery)
            throw std::runtime_error("fixture route failed after delivery");
        if (!self.omitEmpty)
            assert(self.poll() == 0);
    }
};
} // namespace

int main() {
    for (unsigned logical = 1; logical <= 5; ++logical) {
        Fixture f;
        PointerPumpButton button(f.pump, f.pointer, Fixture::run, &f);
        f.reentrant = &button;
        assert(button.press(logical) == 0 && button.owned() && f.state.held == f.pointer.state.masks[logical - 1]);
        assert(f.lastX == -31 && f.lastY == 71);
        f.state.x = -117;
        f.state.y = 903;
        assert(button.release() == 0 && !button.owned() && f.state.held == 0 && f.ups == 1);
        assert(f.lastX == -117 && f.lastY == 903);
        assert(button.release() == 0 && f.ups == 1);
    }
    for (unsigned failure = 0; failure < 3; ++failure) {
        Fixture f;
        PointerPumpButton button(f.pump, f.pointer, Fixture::run, &f);
        assert(button.press(5) == 0);
        f.throwAfterDelivery = failure == 0;
        f.omitEmpty = failure == 1;
        f.ignoreUp = failure == 2;
        assert(button.release() != 0 && button.owned() && f.ups == 1);
        f.throwAfterDelivery = f.omitEmpty = f.ignoreUp = false;
        assert(button.release() != 0 && button.owned() && f.ups == 1);
    }
    {
        Fixture f;
        f.state.held = f.pointer.state.masks[4];
        PointerPumpButton button(f.pump, f.pointer, Fixture::run, &f);
        assert(button.press(5) == 0 && button.owned() && f.downs == 1);
        assert(button.release() == 0 && !button.owned() && f.ups == 1 && f.state.held == 0);
        assert(button.release() == 0 && f.ups == 1);
    }
    {
        Fixture f;
        PointerPumpButton button(f.pump, f.pointer, Fixture::run, &f);
        assert(button.press(4) == 0);
        failClock = true;
        assert(button.release() == EIO && button.owned() && f.ups == 0);
        failClock = false;
        assert(button.release() == EIO && !button.owned() && f.ups == 1);
    }
    {
        Fixture f;
        f.replaceBeforePoll = true;
        PointerPumpButton button(f.pump, f.pointer, Fixture::run, &f);
        assert(button.press(1) == ESTALE && !button.owned() && f.downs == 0);
    }
    {
        Fixture f;
        std::unique_ptr<PointerPumpButton> button;
        std::thread evaluation([&] {
            button = std::make_unique<PointerPumpButton>(f.pump, f.pointer, Fixture::run, &f,
                                                         static_cast<pid_t>(syscall(SYS_gettid)));
            assert(button->press(5) == 0);
        });
        evaluation.join();
        assert(button->release() == 0 && !button->owned());
    }
    {
        Fixture f;
        PointerPump pointer(f.pump, f.pointer, Fixture::run, &f, Fixture::guard, &f);
        f.guardReentrant = &pointer;
        PointerMoveProgress movement;
        assert(pointer.move({131, 281}, movement) == 0 && f.enters == 1 && f.moves == 1);
        assert(movement.enter.emptyObserved && movement.move.emptyObserved);
        assert(pointer.move({211, 319}, movement) == 0 && f.enters == 1 && f.moves == 2);
        assert(!movement.enter.delivered && movement.move.delivered);
        for (int direction : {-1, 1}) {
            EventPumpProgress progress;
            assert(pointer.wheel(direction, progress) == 0 && progress.emptyObserved && f.lastWheel == direction);
            assert(f.lastX == 211 && f.lastY == 319);
        }
        std::thread other([&] {
            EventPumpProgress progress;
            assert(pointer.wheel(1, progress) == EPERM);
        });
        other.join();
    }
    {
        Fixture f;
        f.invalidateOnEnter = true;
        PointerPump pointer(f.pump, f.pointer, Fixture::run, &f, Fixture::guard, &f);
        PointerMoveProgress progress;
        assert(pointer.move({11, 23}, progress) == ESTALE && f.enters == 1 && f.moves == 0);
        assert(progress.enter.emptyObserved && !progress.move.delivered && f.calls == 1);
    }
    {
        Fixture f;
        PumpInputEmitter emitter(f.pump, f.keyboard, f.pointer, Fixture::run, &f, Fixture::guard, &f);
        InputSequence sequence(
            {{2, {{InputDevice::Keyboard, 42}, {InputDevice::Mouse, 5}}, InputPosition{51, 79}, 1, {}}});
        sequence.beforeTick(100, emitter);
        sequence.afterTick(100);
        assert(sequence.hasHeldInput() && emitter.owned() && f.downs == 2 && f.wheels == 1 && f.enters == 1 &&
               f.moves == 1);
        sequence.beforeTick(101, emitter);
        sequence.afterTick(101);
        sequence.beforeTick(102, emitter);
        assert(sequence.state() == InputSequenceState::Succeeded && !emitter.owned() && f.ups == 2 &&
               sequence.ticks() == 2);
    }
    for (const InputButton button : {InputButton{InputDevice::Keyboard, 42}, InputButton{InputDevice::Mouse, 4}}) {
        Fixture f;
        PumpInputEmitter emitter(f.pump, f.keyboard, f.pointer, Fixture::run, &f, Fixture::guard, &f);
        InputSequence sequence({{10, {button}, {}, 0, {}}});
        sequence.beforeTick(100, emitter);
        sequence.afterTick(100);
        sequence.cancel("fixture caller cancelled");
        failClock = true;
        sequence.cleanup(emitter);
        assert(sequence.state() == InputSequenceState::Releasing && emitter.owned() && f.ups == 0);
        failClock = false;
        sequence.cleanup(emitter);
        assert(sequence.state() == InputSequenceState::Aborted && !emitter.owned() && f.ups == 1);
        assert(sequence.reason() == "fixture caller cancelled");
        sequence.cleanup(emitter);
        assert(f.ups == 1);
    }
    {
        Fixture f;
        f.state.held = f.pointer.state.masks[2];
        PumpInputEmitter emitter(f.pump, f.keyboard, f.pointer, Fixture::run, &f, Fixture::guard, &f);
        InputSequence sequence({{1, {{InputDevice::Mouse, 3}}, {}, 0, {}}});
        sequence.beforeTick(1, emitter);
        assert(emitter.owned() && f.downs == 1);
        sequence.afterTick(1);
        sequence.beforeTick(2, emitter);
        assert(sequence.state() == InputSequenceState::Succeeded && !emitter.owned() && f.ups == 1);
        assert(f.state.held == 0);
    }
}
