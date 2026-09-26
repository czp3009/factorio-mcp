#include "input_sequence.h"
#include <cassert>
#include <stdexcept>
#include <string>
#include <vector>

struct Emitter : InputEmitter {
    std::vector<std::string> events;
    bool failDown{}, failUp{};
    bool failWheel{}, failMove{};

    void move(InputPosition position) override {
        events.push_back("move:" + std::to_string(position.x) + "," + std::to_string(position.y));
        if (failMove)
            throw std::runtime_error("Move dispatch failed");
    }

    void button(InputButton button, bool down) override {
        events.push_back(std::string(down ? "+" : "-") + std::to_string(button.code));
        bool &failure = down ? failDown : failUp;
        if (failure) {
            failure = false;
            throw std::runtime_error("Injected dispatch failure");
        }
    }

    void wheel(int32_t direction) override {
        events.push_back("wheel:" + std::to_string(direction));
        if (failWheel)
            throw std::runtime_error("Wheel dispatch failed");
    }
};

static constexpr InputButton w{InputDevice::Keyboard, 10}, d{InputDevice::Keyboard, 20};
static constexpr InputButton left{InputDevice::Mouse, 1};

static void motionPreservesHeldButtonsAndDistinctTicks() {
    InputSequence sequence({{4, {left, w}, InputPosition{10, 20}, 0, {{2, {30, 40}}, {4, {50, 60}}}}, {1, {left}, {}}});
    Emitter emitter;
    const std::vector<std::vector<std::string>> expected{
        {"move:10,20", "+1", "+10"}, {"move:30,40"}, {}, {"move:50,60"}, {"-10", "-1", "+1"}, {"-1"}};
    for (uint64_t i = 0; i < expected.size(); ++i) {
        emitter.events.clear();
        sequence.beforeTick(200 + i, emitter);
        sequence.beforeTick(200 + i, emitter);
        assert(emitter.events == expected[i]);
        sequence.afterTick(200 + i);
        sequence.beforeTick(200 + i, emitter);
        assert(emitter.events == expected[i]);
    }
    assert(sequence.state() == InputSequenceState::Succeeded && sequence.ticks() == 5 && sequence.completed() == 2);
    for (bool fail : {false, true}) {
        InputSequence interrupted({{5, {left}, {}, 0, {{2, {30, 40}}, {4, {50, 60}}}}});
        emitter.events.clear();
        interrupted.beforeTick(1, emitter);
        interrupted.afterTick(1);
        if (fail) {
            emitter.failMove = true;
            interrupted.beforeTick(2, emitter);
            emitter.failMove = false;
        } else {
            interrupted.cancel("Fixture cancelled before motion");
        }
        interrupted.cleanup(emitter);
        interrupted.beforeTick(4, emitter);
        assert(interrupted.state() == InputSequenceState::Aborted && !interrupted.hasHeldInput());
        assert((emitter.events ==
                (fail ? std::vector<std::string>{"+1", "move:30,40", "-1"} : std::vector<std::string>{"+1", "-1"})));
    }
    for (const auto &motion : std::vector<std::vector<InputMotion>>{{{1, {0, 0}}},
                                                                    {{6, {0, 0}}},
                                                                    {{2, {-1, 0}}},
                                                                    {{3, {0, 0}}, {2, {0, 0}}},
                                                                    {{2, {0, 0}}, {2, {1, 1}}},
                                                                    std::vector<InputMotion>(65, {2, {0, 0}})}) {
        bool rejected = false;
        try {
            InputSequence invalid({{5, {left}, {}, 0, motion}});
        } catch (const std::invalid_argument &) {
            rejected = true;
        }
        assert(rejected);
    }
}

static void wheelIsAnImpulseAndIsNeverReplayed() {
    InputSequence sequence({{3, {w}, InputPosition{10, 20}, 1}, {1, {}, {}, -1}});
    Emitter emitter;
    for (uint64_t tick = 1; tick <= 5; ++tick) {
        sequence.beforeTick(tick, emitter);
        sequence.beforeTick(tick, emitter);
        sequence.afterTick(tick);
    }
    assert(sequence.state() == InputSequenceState::Succeeded);
    assert((emitter.events == std::vector<std::string>{"move:10,20", "+10", "wheel:1", "-10", "wheel:-1"}));
    InputSequence failing({{3, {w}, {}, 1}, {1, {}, {}, -1}});
    emitter.events.clear();
    emitter.failWheel = true;
    failing.beforeTick(1, emitter);
    failing.beforeTick(2, emitter);
    failing.cleanup(emitter);
    assert(failing.state() == InputSequenceState::Aborted && !failing.hasHeldInput());
    assert((emitter.events == std::vector<std::string>{"+10", "wheel:1", "-10"}));
}

static void adjacentStepsAndDistinctTicks() {
    InputSequence sequence({{2, {w}, {}}, {3, {d}, {}}, {1, {}, {}}, {2, {w, d}, {}}, {1, {w}, {}}});
    Emitter emitter;
    const std::vector<std::vector<std::string>> expected{{"+10"},        {}, {"-10", "+20"},        {},     {}, {"-20"},
                                                         {"+10", "+20"}, {}, {"-20", "-10", "+10"}, {"-10"}};
    for (uint64_t i = 0; i < expected.size(); ++i) {
        emitter.events.clear();
        sequence.beforeTick(100 + i, emitter);
        // Re-entry and repeated callbacks do not create another input evaluation or chord.
        sequence.beforeTick(100 + i, emitter);
        assert(emitter.events == expected[i]);
        sequence.afterTick(100 + i);
        sequence.afterTick(100 + i);
        sequence.beforeTick(100 + i, emitter);
        assert(emitter.events == expected[i]);
    }
    assert(sequence.state() == InputSequenceState::Succeeded);
    assert(sequence.ticks() == 9 && sequence.completed() == 5 && !sequence.hasHeldInput());
}

static void cancellationDoesNotNeedAnotherTick() {
    InputSequence sequence({{100000, {w, left}, InputPosition{42, 24}}});
    Emitter emitter;
    sequence.beforeTick(1, emitter);
    sequence.afterTick(1);
    assert((emitter.events == std::vector<std::string>{"move:42,24", "+10", "+1"}));
    sequence.cancel("World paused");
    sequence.cleanup(emitter);
    assert(sequence.state() == InputSequenceState::Aborted && sequence.reason() == "World paused");
    assert(sequence.ticks() == 1 && sequence.completed() == 0 && !sequence.hasHeldInput());
    assert((emitter.events == std::vector<std::string>{"move:42,24", "+10", "+1", "-1", "-10"}));
    sequence.beforeTick(2, emitter);
    sequence.afterTick(2);
    assert(sequence.ticks() == 1);
}

static void errorsRetainCleanupOwnership() {
    InputSequence sequence({{2, {w, d}, {}}});
    Emitter emitter;
    emitter.failDown = emitter.failUp = true;
    sequence.beforeTick(10, emitter);
    assert(sequence.state() == InputSequenceState::Releasing && sequence.hasHeldInput());
    sequence.cleanup(emitter);
    assert(sequence.state() == InputSequenceState::Aborted && !sequence.hasHeldInput());
    assert((emitter.events == std::vector<std::string>{"+10", "-10", "-10"}));
    assert(sequence.ticks() == 0);
}

static void clockDiscontinuityAbortsWithoutAnotherDown() {
    for (uint64_t next : {9u, 12u}) {
        InputSequence sequence({{1, {w}, {}}, {1, {d}, {}}});
        Emitter emitter;
        sequence.beforeTick(10, emitter);
        sequence.afterTick(10);
        sequence.beforeTick(next, emitter);
        assert(sequence.state() == InputSequenceState::Aborted && sequence.ticks() == 1);
        assert((emitter.events == std::vector<std::string>{"+10", "-10"}));
    }
}

static void validationPrecedesReplacement() {
    for (const std::vector<InputStep> invalid :
         {std::vector<InputStep>{{0, {}, {}}}, std::vector<InputStep>{{1, {w, w}, {}}},
          std::vector<InputStep>{{1, {{static_cast<InputDevice>(9), 1}}, {}}},
          std::vector<InputStep>{{1, {left}, InputPosition{-1, 0}}}}) {
        bool rejected = false;
        try {
            InputSequence::validate(invalid);
        } catch (const std::invalid_argument &) {
            rejected = true;
        }
        assert(rejected);
    }
    InputSequence empty({});
    assert(empty.state() == InputSequenceState::Succeeded && empty.ticks() == 0);
}

static void incompleteEvaluationNeverAdvancesTheSequence() {
    InputSequence sequence({{1, {w}, {}}, {1, {d}, {}}});
    Emitter emitter;
    sequence.beforeTick(10, emitter);
    sequence.beforeTick(11, emitter);
    assert(sequence.state() == InputSequenceState::Aborted);
    assert(sequence.ticks() == 0 && !sequence.hasHeldInput());
    assert((emitter.events == std::vector<std::string>{"+10", "-10"}));
}

static void lifecycleCancellationDuringDispatchStopsFurtherDowns() {
    struct CancellingEmitter : Emitter {
        InputSequence *task{};
        bool onMove{}, onUp{};

        void move(InputPosition position) override {
            Emitter::move(position);
            if (onMove)
                task->cancel("World changed while moving");
        }

        void button(InputButton button, bool down) override {
            Emitter::button(button, down);
            if (!onMove && down != onUp)
                task->cancel("World changed during a button event");
        }
    };

    for (unsigned phase = 0; phase < 3; ++phase) {
        InputSequence sequence({{1, {w, d}, InputPosition{12, 34}}, {1, {left}, {}}});
        CancellingEmitter emitter;
        emitter.task = &sequence;
        emitter.onMove = phase == 0;
        emitter.onUp = phase == 2;
        sequence.beforeTick(10, emitter);
        if (phase == 2) {
            sequence.afterTick(10);
            sequence.beforeTick(11, emitter);
        }
        assert(sequence.state() == InputSequenceState::Aborted && !sequence.hasHeldInput());
        const std::vector<std::string> expected[] = {
            {"move:12,34"}, {"move:12,34", "+10", "-10"}, {"move:12,34", "+10", "+20", "-20", "-10"}};
        assert(emitter.events == expected[phase]);
    }
}

int main() {
    motionPreservesHeldButtonsAndDistinctTicks();
    wheelIsAnImpulseAndIsNeverReplayed();
    adjacentStepsAndDistinctTicks();
    cancellationDoesNotNeedAnotherTick();
    errorsRetainCleanupOwnership();
    clockDiscontinuityAbortsWithoutAnotherDown();
    validationPrecedesReplacement();
    incompleteEvaluationNeverAdvancesTheSequence();
    lifecycleCancellationDuringDispatchStopsFurtherDowns();
}
