#include "input_sequence.h"
#include <algorithm>
#include <limits>
#include <stdexcept>
#include <utility>

void InputSequence::validate(const std::vector<InputStep> &steps) {
    if (steps.size() > 256)
        throw std::invalid_argument("Input sequence exceeds 256 steps");
    for (const auto &step : steps) {
        if (!step.ticks || step.buttons.size() > 8)
            throw std::invalid_argument("Input step requires positive ticks and at most eight buttons");
        if (step.wheel < -1 || step.wheel > 1)
            throw std::invalid_argument("Invalid wheel direction");
        for (size_t i = 0; i < step.buttons.size(); ++i) {
            const auto button = step.buttons[i];
            if ((button.device != InputDevice::Keyboard && button.device != InputDevice::Mouse) || !button.code)
                throw std::invalid_argument("Invalid input button");
            if (std::find(step.buttons.begin(), step.buttons.begin() + i, button) != step.buttons.begin() + i)
                throw std::invalid_argument("Duplicate input button in chord");
        }
        if (step.position && (step.position->x < 0 || step.position->y < 0))
            throw std::invalid_argument("Viewport coordinates must be nonnegative");
        if (step.motion.size() > 64)
            throw std::invalid_argument("Mouse motion exceeds 64 points");
        uint32_t previous = 1;
        for (const auto &point : step.motion) {
            if (point.tick <= previous || point.tick > step.ticks || point.position.x < 0 || point.position.y < 0)
                throw std::invalid_argument("Invalid mouse motion position or tick");
            previous = point.tick;
        }
    }
}

InputSequence::InputSequence(std::vector<InputStep> values) : steps(std::move(values)) {
    validate(steps);
    held.reserve(8);
    if (steps.empty())
        stateValue = InputSequenceState::Succeeded;
}

void InputSequence::release(InputEmitter &emitter) {
    while (!held.empty()) {
        emitter.button(held.back(), false);
        // The adapter reconciles native ownership on repeated cleanup calls; it must not replay an uncertain up.
        held.pop_back();
    }
}

void InputSequence::fail(const char *message) {
    if (reasonValue.empty())
        reasonValue = std::string(message).substr(0, 1024);
    preparedTick.reset();
    stateValue = InputSequenceState::Releasing;
}

void InputSequence::beforeTick(uint64_t tick, InputEmitter &emitter) {
    if (stateValue == InputSequenceState::Releasing) {
        cleanup(emitter);
        return;
    }
    if (stateValue != InputSequenceState::Running)
        return;
    if (preparedTick) {
        if (*preparedTick != tick) {
            fail("Another input tick began before the previous evaluation completed");
            cleanup(emitter);
        }
        return;
    }
    if (previousTick && tick == *previousTick)
        return;
    if (previousTick && (*previousTick == std::numeric_limits<uint64_t>::max() || tick != *previousTick + 1)) {
        fail("Input clock changed discontinuously");
        cleanup(emitter);
        return;
    }
    try {
        if (!remaining) {
            release(emitter);
            if (nextStep)
                completedSteps = nextStep;
            if (stateValue != InputSequenceState::Running) {
                cleanup(emitter);
                return;
            }
            if (nextStep == steps.size()) {
                stateValue = InputSequenceState::Succeeded;
                return;
            }
            const auto &step = steps[nextStep];
            if (step.position)
                emitter.move(*step.position);
            if (stateValue != InputSequenceState::Running) {
                cleanup(emitter);
                return;
            }
            for (const auto button : step.buttons) {
                // Record ownership before dispatch, including an exception after the state update.
                held.push_back(button);
                emitter.button(button, true);
                if (stateValue != InputSequenceState::Running) {
                    cleanup(emitter);
                    return;
                }
            }
            ++nextStep;
            if (step.wheel) {
                emitter.wheel(step.wheel);
                if (stateValue != InputSequenceState::Running) {
                    cleanup(emitter);
                    return;
                }
            }
            remaining = step.ticks;
            nextMotion = 0;
        }
        const auto &step = steps[nextStep - 1];
        const auto offset = step.ticks - remaining + 1;
        if (nextMotion < step.motion.size() && step.motion[nextMotion].tick == offset) {
            // Advance before dispatch: an uncertain move is never replayed after an exception.
            emitter.move(step.motion[nextMotion++].position);
            if (stateValue != InputSequenceState::Running) {
                cleanup(emitter);
                return;
            }
        }
        preparedTick = tick;
    } catch (const std::exception &error) {
        fail(error.what());
        cleanup(emitter);
    }
}

void InputSequence::afterTick(uint64_t tick) {
    if (stateValue != InputSequenceState::Running || !preparedTick)
        return;
    if (*preparedTick != tick) {
        fail("Input evaluation completed with a different tick");
        return;
    }
    preparedTick.reset();
    previousTick = tick;
    --remaining;
    ++evaluatedTicks;
}

void InputSequence::cancel(const char *reason) {
    if (stateValue == InputSequenceState::Running)
        fail(reason);
}

void InputSequence::cleanup(InputEmitter &emitter) {
    if (stateValue != InputSequenceState::Releasing)
        return;
    try {
        release(emitter);
        stateValue = InputSequenceState::Aborted;
    } catch (const std::exception &) {
        // Remain in cleanup; the next eligible phase reconciles the outstanding release obligations.
    }
}

InputSequenceState InputSequence::state() const {
    return stateValue;
}

const std::string &InputSequence::reason() const {
    return reasonValue;
}

uint64_t InputSequence::ticks() const {
    return evaluatedTicks;
}

size_t InputSequence::completed() const {
    return completedSteps;
}

bool InputSequence::hasHeldInput() const {
    return !held.empty();
}
