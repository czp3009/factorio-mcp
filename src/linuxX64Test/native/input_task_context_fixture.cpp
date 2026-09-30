#include "input_task_context.h"
#include <cassert>
#include <cerrno>
#include <functional>
#include <stdexcept>
#include <vector>

namespace {
InputContext current{0x1000, 0x2000, 0x3000, 0x4000, 0x5000, 10, 0, false};
const InputContext initial = current;
int readFailure = 0;
unsigned reads = 0;
std::function<void()> duringRead;

int readContext(const FmLinuxInputContextConfig &, const uint32_t *cancel, InputContext &output) {
    ++reads;
    output = current;
    if (duringRead)
        duringRead();
    return *cancel ? ECANCELED : readFailure;
}

const InputButton first{InputDevice::Keyboard, 4};
const InputButton second{InputDevice::Mouse, 1};

struct NativeEmitter : InputEmitter {
    std::vector<InputButton> held;
    unsigned downs = 0, ups = 0, moves = 0, wheels = 0;
    bool blockRelease = false;
    std::function<void()> callback;

    void move(InputPosition) override {
        ++moves;
        if (callback)
            callback();
    }

    void button(InputButton button, bool down) override {
        if (down) {
            ++downs;
            held.push_back(button);
            if (callback)
                callback();
        } else if (!held.empty() && held.back() == button) {
            if (blockRelease)
                throw std::runtime_error("release unavailable before native entry");
            ++ups;
            held.pop_back();
        }
    }

    void wheel(int32_t) override {
        ++wheels;
        if (callback)
            callback();
    }
};

void admission() {
    FmLinuxInputContextConfig config{};
    uint32_t cancel = 0;
    InputContext output;
    ViewLifetime lifetime;
    InputTaskContext stale(lifetime, readContext);
    duringRead = [&] { lifetime.retired(current.view); };
    assert(stale.bind(config, &cancel, output) == ESTALE && !stale.owned() && !output.view);
    duringRead = {};
    InputTaskContext task(lifetime, readContext);
    assert(task.bind(config, &cancel, output) == 0 && output.view == current.view);
    InputTaskContext other(lifetime, readContext);
    assert(other.bind(config, &cancel, output) == EBUSY);
    other.release();
    assert(lifetime.valid(current.view));
    ++current.tick;
    current.paused = true;
    current.stopped = 3;
    assert(task.read(output) == 0 && output.tick == current.tick && output.paused && output.stopped == 3);
    task.release();
    task.release();
    assert(!lifetime.owned() && task.read(output) == EINVAL && !output.view);
    assert(task.bind(config, &cancel, output) == EALREADY);
    cancel = 1;
    InputTaskContext canceled(lifetime, readContext);
    assert(canceled.bind(config, &cancel, output) == ECANCELED && !lifetime.owned());
    current = initial;
}

void replacement() {
    FmLinuxInputContextConfig config{};
    uint32_t cancel = 0;
    // Changes to any association invalidate the task permanently, even if the former values return later.
    for (auto member : {&InputContext::game, &InputContext::source, &InputContext::player,
                        &InputContext::map, &InputContext::view}) {
        ViewLifetime lifetime;
        InputTaskContext task(lifetime, readContext);
        InputContext output;
        assert(task.bind(config, &cancel, output) == 0);
        current.*member += 0x10000;
        assert(task.read(output) == ESTALE && !output.game && !output.view);
        current = initial;
        assert(task.read(output) == ESTALE && task.owned());
        task.release();
    }
}

void sequence() {
    FmLinuxInputContextConfig config{};
    uint32_t cancel = 0;
    InputContext output;
    ViewLifetime lifetime;
    InputTaskContext task(lifetime, readContext);
    assert(task.bind(config, &cancel, output) == 0);
    NativeEmitter native;
    GuardedInputEmitter emitter(task, native);
    InputSequence sequence({{2, {first, second}, {}, 0, {}}});
    sequence.beforeTick(10, emitter);
    sequence.afterTick(10);
    sequence.beforeTick(10, emitter);
    sequence.afterTick(10);
    assert(sequence.ticks() == 1 && native.downs == 2 && native.ups == 0);
    current.tick = 11;
    sequence.beforeTick(11, emitter);
    sequence.afterTick(11);
    current.tick = 12;
    sequence.beforeTick(12, emitter);
    assert(sequence.state() == InputSequenceState::Succeeded && sequence.ticks() == 2 && sequence.completed() == 1);
    assert(native.ups == 2 && native.held.empty());
    task.release();
    current = initial;
}

void reentrantRetirement() {
    FmLinuxInputContextConfig config{};
    uint32_t cancel = 0;
    InputContext output;
    ViewLifetime lifetime;
    InputTaskContext task(lifetime, readContext);
    assert(task.bind(config, &cancel, output) == 0);
    NativeEmitter native;
    GuardedInputEmitter emitter(task, native);
    unsigned retirementReads = 0;
    native.callback = [&] {
        retirementReads = reads;
        lifetime.retired(current.view);
    };
    native.blockRelease = true;
    InputSequence sequence({{3, {first, second}, {}, 0, {}}});
    sequence.beforeTick(10, emitter);
    assert(sequence.state() == InputSequenceState::Releasing && native.downs == 1 && native.ups == 0);
    assert(task.failure() == ESTALE && task.owned() && lifetime.owned() && sequence.hasHeldInput());
    assert(reads == retirementReads);
    // No additional chord press or replay, even though all raw pointer values still match.
    sequence.beforeTick(11, emitter);
    assert(native.downs == 1 && sequence.ticks() == 0);
    readFailure = EFAULT;
    const auto before = reads;
    native.blockRelease = false;
    sequence.cleanup(emitter);
    assert(sequence.state() == InputSequenceState::Aborted && !sequence.hasHeldInput());
    assert(native.ups == 1 && reads == before);
    task.release();
    assert(!lifetime.owned());
    readFailure = 0;
}

void callbackChecks() {
    FmLinuxInputContextConfig config{};
    uint32_t cancel = 0;
    for (unsigned operation = 0; operation < 3; ++operation) {
        ViewLifetime lifetime;
        InputTaskContext task(lifetime, readContext);
        InputContext output;
        assert(task.bind(config, &cancel, output) == 0);
        NativeEmitter native;
        native.callback = [] { current.player += 0x10000; };
        GuardedInputEmitter emitter(task, native);
        const auto dispatch = [&] {
            if (operation == 0) emitter.move({10, 20});
            else if (operation == 1) emitter.wheel(1);
            else emitter.button(first, true);
        };
        try {
            dispatch();
            assert(false);
        } catch (const std::runtime_error &) {}
        current = initial;
        try {
            dispatch();
            assert(false);
        } catch (const std::runtime_error &) {}
        assert(native.moves + native.wheels + native.downs == 1);
        emitter.button(first, false);
        task.release();
    }
}

void stoppedWorldCleanup() {
    FmLinuxInputContextConfig config{};
    uint32_t cancel = 0;
    for (unsigned condition = 0; condition < 3; ++condition) {
        ViewLifetime lifetime;
        InputTaskContext task(lifetime, readContext);
        InputContext output;
        assert(task.bind(config, &cancel, output) == 0);
        NativeEmitter native;
        native.callback = [&] {
            if (condition == 0) current.paused = true;
            else if (condition == 1) current.stopped = 1;
            else readFailure = EFAULT;
        };
        GuardedInputEmitter emitter(task, native);
        InputSequence sequence({{3, {first, second}, {}, 0, {}}});
        sequence.beforeTick(10, emitter);
        assert(sequence.state() == InputSequenceState::Aborted && sequence.ticks() == 0);
        assert(native.downs == 1 && native.ups == 1 && native.held.empty());
        task.release();
        current = initial;
        readFailure = 0;
    }
}
} // namespace

int main() {
    admission();
    replacement();
    sequence();
    reentrantRetirement();
    callbackChecks();
    stoppedWorldCleanup();
}
