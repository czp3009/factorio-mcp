#include "keyboard_route.h"
#include "../../nativeMain/native/input_sequence.h"
#include <cassert>
#include <cerrno>
#include <cstddef>
#include <cstring>
#include <stdexcept>
#include <optional>
#include <sys/syscall.h>
#include <thread>
#include <unistd.h>
#include <vector>

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
    uint32_t type, code;
    double time;
};

bool failClock = false;

uint32_t ticks() {
    if (failClock)
        throw std::runtime_error("fixture clock");
    return 1234;
}

struct Fixture {
    Record records[9];
    Record &record = records[0];
    State state{0, records, records + 9}, replacement;
    Global global{&state};
    Global *root = &global;
    KeyboardRouteConfig config{};
    EventRouteFunctions functions{source, guiEvent, guiLogic, evaluate, update, post};
    unsigned throwStage = 0, rejectStage = 0;
    uint8_t sourceResult = 0;
    bool rejectDown = false, ignoreUp = false, throwOnce = false;
    unsigned pressResolves = 0, releaseResolves = 0;
    std::vector<unsigned> calls;
    KeyboardRouteKey *reentrant = nullptr;

    explicit Fixture(EventUpdateOrder order = EventUpdateOrder::BeforeSource) {
        for (unsigned index = 0; index < 9; ++index)
            records[index].code = 42 + index;
        config.owner = {reinterpret_cast<uintptr_t>(&root),
                        sizeof(Global),
                        offsetof(Global, state),
                        sizeof(State),
                        offsetof(State, buttons),
                        sizeof(Event),
                        offsetof(Event, type),
                        offsetof(Event, time),
                        offsetof(Event, code),
                        3,
                        4,
                        {1, 2, 3},
                        {2, 4, 8}};
        config.keys = {0,
                       offsetof(State, begin),
                       offsetof(State, end),
                       sizeof(Record),
                       offsetof(Record, code),
                       offsetof(Record, value),
                       sizeof(Value),
                       offsetof(Value, held),
                       offsetof(Value, blocked)};
        config.event.extent = sizeof(Event);
        config.event.type = offsetof(Event, type);
        config.event.time = offsetof(Event, time);
        config.event.code = offsetof(Event, code);
        config.event.press = 1;
        config.event.release = 2;
        config.clock = {reinterpret_cast<uintptr_t>(ticks), 1000};
        config.pressOrder = config.releaseOrder = order;
    }

    KeyboardRouteKey key() {
        return KeyboardRouteKey(config, functions, getpid(), getpid(), pressReceiver, releaseReceiver, this);
    }

    KeyboardRouteKeys keys() {
        return KeyboardRouteKeys(config, functions, getpid(), getpid(), pressReceiver, releaseReceiver, this);
    }

    static int pressReceiver(EventRouteStage stage, void *context, void *&receiver) noexcept {
        auto &self = *static_cast<Fixture *>(context);
        ++self.pressResolves;
        receiver = context;
        return self.rejectDown || self.rejectStage == static_cast<unsigned>(stage) ? ESTALE : 0;
    }

    static int releaseReceiver(EventRouteStage stage, void *context, void *&receiver) noexcept {
        auto &self = *static_cast<Fixture *>(context);
        ++self.releaseResolves;
        receiver = context;
        return self.rejectStage == static_cast<unsigned>(stage) ? ESTALE : 0;
    }

    void entered(EventRouteStage stage) {
        const auto id = static_cast<unsigned>(stage);
        calls.push_back(id);
        if (reentrant)
            assert(reentrant->release() == EBUSY);
        if (throwStage == id) {
            if (throwOnce)
                throwStage = 0;
            throw std::runtime_error("fixture entered native stage");
        }
    }

    static Event event(const void *bytes) {
        Event value;
        std::memcpy(&value, bytes, sizeof(value));
        assert(value.code >= 42 && value.code <= 50 && value.time == 1.234 && (value.type == 1 || value.type == 2));
        return value;
    }

    static uint8_t source(void *self, const void *bytes) {
        event(bytes);
        auto &fixture = *static_cast<Fixture *>(self);
        fixture.entered(EventRouteStage::Source);
        return fixture.sourceResult;
    }

    static void guiEvent(void *self, const void *bytes) {
        event(bytes);
        static_cast<Fixture *>(self)->entered(EventRouteStage::GuiEvent);
    }

    static void guiLogic(void *self, bool value) {
        assert(!value);
        static_cast<Fixture *>(self)->entered(EventRouteStage::GuiLogic);
    }

    static void evaluate(void *self) {
        static_cast<Fixture *>(self)->entered(EventRouteStage::Evaluation);
    }

    static void update(void *self, const void *bytes) {
        auto &fixture = *static_cast<Fixture *>(self);
        const auto &value = event(bytes);
        if (value.type == 1 || !fixture.ignoreUp)
            fixture.records[value.code - 42].value.held = value.type == 1;
        fixture.entered(EventRouteStage::Update);
    }

    static void post(void *self, const void *bytes) {
        event(bytes);
        static_cast<Fixture *>(self)->entered(EventRouteStage::PostUpdate);
    }
};
} // namespace

class SequenceEmitter final : public InputEmitter {
  public:
    explicit SequenceEmitter(KeyboardRouteKeys &keys) : keys_(keys) {}

    void button(InputButton button, bool down) override {
        assert(button.device == InputDevice::Keyboard);
        if (keys_.button(button.code, down))
            throw std::runtime_error("fixture keyboard dispatch failed");
    }

    void move(InputPosition) override {
        throw std::runtime_error("unexpected fixture motion");
    }

    void wheel(int32_t) override {
        throw std::runtime_error("unexpected fixture wheel");
    }

  private:
    KeyboardRouteKeys &keys_;
};

int main() {
    {
        Fixture f;
        auto keys = f.keys();
        SequenceEmitter emitter(keys);
        InputSequence sequence({InputStep{1, {{InputDevice::Keyboard, 42}}, {}, 0, {}}});
        f.throwStage = static_cast<unsigned>(EventRouteStage::Update);
        f.throwOnce = true;
        sequence.beforeTick(1, emitter);
        assert(sequence.state() == InputSequenceState::Aborted);
        assert(!sequence.hasHeldInput() && !keys.owned() && !f.record.value.held);
        assert(!sequence.reason().empty());
    }
    {
        Fixture f;
        auto keys = f.keys();
        SequenceEmitter emitter(keys);
        f.records[1].value.blocked = 1;
        InputSequence sequence({InputStep{1, {{InputDevice::Keyboard, 42}, {InputDevice::Keyboard, 43}}, {}, 0, {}}});
        sequence.beforeTick(1, emitter);
        assert(sequence.hasHeldInput() && keys.owned());
        sequence.afterTick(1);
        sequence.beforeTick(2, emitter);
        assert(sequence.state() == InputSequenceState::Succeeded);
        assert(!sequence.hasHeldInput() && !keys.owned() && !f.record.value.held);
        assert(f.records[1].value.blocked && !f.records[1].value.held);
    }
    {
        Fixture f;
        auto keys = f.keys();
        SequenceEmitter emitter(keys);
        InputSequence sequence({InputStep{1, {{InputDevice::Keyboard, 42}}, {}, 0, {}}});
        sequence.beforeTick(1, emitter);
        sequence.afterTick(1);
        f.throwStage = static_cast<unsigned>(EventRouteStage::Source);
        sequence.cancel("fixture cancel");
        sequence.cleanup(emitter);
        assert(sequence.state() == InputSequenceState::Releasing && keys.owned());
        const auto calls = f.calls.size();
        f.throwStage = 0;
        sequence.cleanup(emitter);
        assert(sequence.state() == InputSequenceState::Releasing && keys.owned() && f.calls.size() == calls);
    }
    {
        Fixture f;
        auto keys = f.keys();
        for (unsigned code = 42; code < 50; ++code)
            assert(keys.button(code, true) == 0);
        const auto calls = f.calls.size();
        assert(keys.button(50, true) == ENOSPC && f.calls.size() == calls);
        assert(keys.button(42, true) == EALREADY && f.calls.size() == calls);
        assert(keys.button(50, false) == 0 && f.calls.size() == calls);
        for (unsigned code = 42; code < 50; ++code)
            assert(keys.button(code, false) == 0);
        assert(!keys.owned());
        assert(keys.button(42, true) == 0 && keys.button(42, false) == 0);
    }

    {
        Fixture f;
        std::optional<KeyboardRouteKey> key;
        std::thread worker([&] {
            key.emplace(f.config, f.functions, static_cast<pid_t>(syscall(SYS_gettid)), getpid(),
                        Fixture::pressReceiver, Fixture::releaseReceiver, &f);
            assert(key->press(42) == 0 && key->owned());
        });
        worker.join();
        assert(key->press(42) == EPERM);
        assert(key->release() == 0 && !key->owned());
    }
    {
        Fixture f;
        auto key = f.key();
        assert(key.press(42) == 0);
        f.rejectStage = static_cast<unsigned>(EventRouteStage::Update);
        assert(key.release() == ESTALE && key.owned() && !key.releaseProgress().entered);
        f.rejectStage = 0;
        assert(key.release() == ESTALE && !key.owned());
    }
    {
        Fixture f;
        auto key = f.key();
        assert(key.press(42) == 0);
        f.rejectStage = static_cast<unsigned>(EventRouteStage::Evaluation);
        assert(key.release() == ESTALE && key.owned() && key.releaseProgress().entered);
        const auto calls = f.calls.size();
        f.rejectStage = 0;
        assert(key.release() == ESTALE && key.owned() && f.calls.size() == calls);
    }

    for (const auto order : {EventUpdateOrder::BeforeSource, EventUpdateOrder::AfterEvaluation}) {
        for (const uint8_t source : {0, 1}) {
            Fixture f(order);
            f.sourceResult = source;
            auto key = f.key();
            f.reentrant = &key;
            std::thread foreign([&] {
                assert(key.press(42) == EPERM);
                assert(key.release() == EPERM);
            });
            foreign.join();
            assert(f.calls.empty());
            assert(key.press(42) == 0 && key.owned() && f.record.value.held);
            assert(key.press(42) == EALREADY);
            f.rejectDown = true; // Cleanup is independent of the new-input world guard.
            assert(key.release() == 0 && !key.owned() && !f.record.value.held);
            const auto completed = f.calls.size();
            assert(key.release() == 0 && f.calls.size() == completed);
            assert(f.releaseResolves && f.pressResolves);
        }
        for (const auto stage : {1u, 2u, 4u, 8u, 16u, 32u}) {
            Fixture f(order);
            auto key = f.key();
            f.throwStage = stage;
            assert(key.press(42) == EFAULT && key.owned());
            f.throwStage = 0;
            assert(key.release() == EFAULT && !key.owned() && !f.record.value.held);

            Fixture uncertain(order);
            auto up = uncertain.key();
            assert(up.press(42) == 0);
            uncertain.throwStage = stage;
            assert(up.release() == EFAULT && up.owned());
            const auto calls = uncertain.calls.size();
            uncertain.throwStage = 0;
            assert(up.release() == EFAULT && up.owned() && uncertain.calls.size() == calls);
        }
    }
    {
        Fixture f;
        auto key = f.key();
        f.record.value.held = 1;
        assert(key.press(42) == 0 && key.owned());
        assert(key.release() == 0 && !key.owned() && !f.record.value.held);
    }
    {
        Fixture f;
        auto key = f.key();
        f.rejectDown = true;
        assert(key.press(42) == ESTALE && !key.owned() && f.calls.empty());
    }
    {
        Fixture f;
        auto key = f.key();
        assert(key.press(42) == 0);
        failClock = true;
        assert(key.release() == EIO && key.owned() && !key.releaseProgress().entered);
        failClock = false;
        assert(key.release() == EIO && !key.owned());
    }
    {
        Fixture f;
        auto key = f.key();
        assert(key.press(42) == 0);
        f.global.state = &f.replacement;
        const auto calls = f.calls.size();
        assert(key.release() == ESTALE && key.owned() && f.calls.size() == calls);
    }
    {
        Fixture f;
        auto key = f.key();
        assert(key.press(42) == 0);
        f.ignoreUp = true;
        assert(key.release() == EPROTO && key.owned());
        const auto calls = f.calls.size();
        f.record.value.held = 0;
        assert(key.release() == EPROTO && !key.owned() && f.calls.size() == calls);
    }
}
