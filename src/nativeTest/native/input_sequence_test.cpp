#include "input_sequence.h"
#include "input_world_projection.h"
#include <cassert>
#include <stdexcept>
#include <string>
#include <vector>

struct Emitter : InputEmitter {
    std::vector<std::string> events;
    std::vector<InputPoint> worldPoints;
    bool failDown{}, failUp{}, failMove{}, failWheel{};
    InputSequence *cancelOnDown{};

    void move(InputPosition point) override {
        events.push_back("move:" + std::to_string(point.x) + "," + std::to_string(point.y));
        if (failMove)
            throw std::runtime_error("Move failed");
    }

    void moveWorld(InputPoint point) override {
        worldPoints.push_back(point);
    }

    void button(InputButton button, bool down) override {
        events.push_back(std::string(down ? "+" : "-") + std::to_string(button.code));
        bool &failure = down ? failDown : failUp;
        if (failure) {
            failure = false;
            throw std::runtime_error("Button dispatch failed");
        }
        if (down && cancelOnDown)
            cancelOnDown->cancel("World unloaded");
    }

    void wheel(int32_t direction) override {
        events.push_back("wheel:" + std::to_string(direction));
        if (failWheel)
            throw std::runtime_error("Wheel failed");
    }
};

static InputEntry key(uint32_t code, std::vector<InputInterval> intervals) {
    return {InputKind::Keyboard, code, std::move(intervals)};
}

static void tick(InputSequence &task, Emitter &emitter, uint64_t nativeTick) {
    task.beforeTick(nativeTick, emitter);
    const auto size = emitter.events.size();
    task.beforeTick(nativeTick, emitter);
    assert(emitter.events.size() == size);
    task.afterTick(nativeTick, emitter);
    const auto completed = task.ticks();
    task.afterTick(nativeTick, emitter);
    task.beforeTick(nativeTick, emitter);
    assert(task.ticks() == completed);
}

static void overlapsAndInclusiveEndpoints() {
    InputSequence task({key(10, {{0, 2}, {3, 3}, {5, 6}}), key(10, {{1, 3}}), key(20, {{2, 2}})});
    Emitter emitter;
    for (uint64_t i = 0; i <= 6; ++i)
        tick(task, emitter, 100 + i);
    assert((emitter.events == std::vector<std::string>{"+10", "+20", "-20", "-10", "+10", "-10"}));
    assert(task.state() == InputSequenceState::Succeeded && task.ticks() == 7 && task.completed() == 3);
    assert(!task.hasHeldInput());
    InputSequence adjacent({key(10, {{0, 0}, {1, 1}})});
    emitter.events.clear();
    tick(adjacent, emitter, 1);
    tick(adjacent, emitter, 2);
    assert((emitter.events == std::vector<std::string>{"+10", "-10", "+10", "-10"}));
    InputSequence internalOverlap({key(10, {{2, 3}, {0, 5}, {2, 3}})});
    emitter.events.clear();
    for (uint64_t i = 0; i <= 5; ++i)
        tick(internalOverlap, emitter, 100 + i);
    assert((emitter.events == std::vector<std::string>{"+10", "-10"}));
    assert(internalOverlap.ticks() == 6 && internalOverlap.completed() == 1 && !internalOverlap.hasHeldInput());
    std::vector<InputInterval> repeated(64, {0, 0});
    InputSequence many({key(10, repeated)});
    emitter.events.clear();
    tick(many, emitter, 1);
    assert((emitter.events == std::vector<std::string>{"+10", "-10"}));
    assert(many.state() == InputSequenceState::Succeeded && many.completed() == 1);
}

static void draggingAndRotation() {
    InputSequence task({
        {InputKind::Motion, 0, {{0, 2}}, false, false, {10, 20}, {12, 20}},
        {InputKind::MouseButton, 1, {{0, 5}}},
        key(30, {{2, 2}}),
        {InputKind::Motion, 0, {{3, 5}}, false, false, {12, 20}, {12, 22}},
    });
    Emitter emitter;
    for (uint64_t i = 0; i <= 5; ++i)
        tick(task, emitter, 500 + i);
    assert((emitter.events == std::vector<std::string>{"move:10,20", "+1", "move:11,20", "move:12,20", "+30", "-30",
                                                       "move:12,20", "move:12,21", "move:12,22", "-1"}));
    assert(task.ticks() == 6 && task.completed() == 4);
}

static void dwellAndParallelPointers() {
    InputSequence task({
        {InputKind::Motion, 0, {{2, 7}}, true, true, {-1.5, 0.5}, {0.5, 0.5}, 2},
        {InputKind::Motion, 0, {{3, 3}, {5, 5}}, false, false, {0, 0}, {10, 20}},
        {InputKind::Wheel, 1, {{0, 2}, {3, 3}}},
    });
    Emitter emitter;
    for (uint64_t i = 0; i <= 7; ++i)
        tick(task, emitter, 1 + i);
    assert(emitter.worldPoints.size() == 6);
    for (size_t i = 0; i < 6; ++i) {
        assert(emitter.worldPoints[i].x == -1.5 + double(i / 2));
        assert(emitter.worldPoints[i].y == 0.5);
    }
    assert((emitter.events == std::vector<std::string>{"wheel:1", "move:10,20", "wheel:1", "move:10,20"}));
    assert(task.state() == InputSequenceState::Succeeded);
}

static void cancellationAndFailures() {
    for (unsigned phase = 0; phase < 5; ++phase) {
        InputSequence task({key(10, {{0, 100}}),
                            key(20, {{0, 100}}),
                            {InputKind::Motion, 0, {{0, 100}}, false, false, {0, 0}, {100, 100}},
                            {InputKind::Wheel, 1, {{0, 100}}}});
        Emitter emitter;
        if (phase == 0)
            emitter.failDown = emitter.failUp = true;
        if (phase == 1)
            emitter.failMove = true;
        if (phase == 2)
            emitter.failWheel = true;
        if (phase == 3)
            emitter.cancelOnDown = &task;
        task.beforeTick(1, emitter);
        if (phase == 4) {
            task.afterTick(1, emitter);
            emitter.failUp = true;
            task.cancel("Cancelled");
        }
        task.cleanup(emitter);
        task.cleanup(emitter);
        assert(task.state() == InputSequenceState::Aborted && !task.hasHeldInput());
        assert(task.completed() == 0);
    }
    for (uint64_t next : {9u, 12u}) {
        InputSequence task({key(10, {{0, 5}})});
        Emitter emitter;
        tick(task, emitter, 10);
        task.beforeTick(next, emitter);
        assert(task.state() == InputSequenceState::Aborted && task.ticks() == 1);
        assert((emitter.events == std::vector<std::string>{"+10", "-10"}));
    }
    InputSequence incomplete({key(10, {{0, 1}})});
    Emitter emitter;
    incomplete.beforeTick(10, emitter);
    incomplete.beforeTick(11, emitter);
    assert(incomplete.state() == InputSequenceState::Aborted && incomplete.ticks() == 0);
}

static void validationAndProjection() {
    for (auto entry : std::vector<InputEntry>{key(0, {{0, 1}}),
                                              key(1, {}),
                                              key(1, {{2, 1}}),

                                              {InputKind::Motion, 0, {{0, 1}}, false, false, {-1, 0}, {0, 0}},
                                              {InputKind::Motion, 0, {{0, 1}}, true, true, {0, 0}, {1, 1}},
                                              {InputKind::Motion, 0, {{0, 1}}, false, false, {0, 0}, {5, 0}, 1}}) {
        bool rejected = false;
        try {
            InputSequence::validate({entry});
        } catch (const std::invalid_argument &) {
            rejected = true;
        }
        assert(rejected);
    }
    InputSequence empty({});
    assert(empty.state() == InputSequenceState::Succeeded);
    auto map = [](int32_t x, int32_t y) { return InputPoint{-5 + double(x) / 4, -10 + double(y) / 8}; };
    const auto pixel = projectInputWorldPoint({-1.5, -2.5}, 100, 100, map);
    assert(pixel.x == 14 && pixel.y == 60);
    const auto nearest = projectInputWorldPoint({-1.4, -2.45}, 100, 100, map);
    assert(nearest.x == 14 && nearest.y == 60);
    bool rejected = false;
    try {
        projectInputWorldPoint({-100, 0}, 100, 100, map);
    } catch (const std::out_of_range &) {
        rejected = true;
    }
    assert(rejected);
}

int main() {
    overlapsAndInclusiveEndpoints();
    draggingAndRotation();
    dwellAndParallelPointers();
    cancellationAndFailures();
    validationAndProjection();
}
