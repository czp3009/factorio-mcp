#pragma once
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

enum class InputDevice : uint32_t { Keyboard, Mouse };

struct InputButton {
    InputDevice device;
    uint32_t code;
    bool operator==(const InputButton &) const = default;
};

struct InputPosition {
    int32_t x, y;
};

struct InputMotion {
    uint32_t tick;
    InputPosition position;
};

struct InputStep {
    uint32_t ticks;
    std::vector<InputButton> buttons;
    std::optional<InputPosition> position;
    int32_t wheel{};
    std::vector<InputMotion> motion;
};

// Implemented by the game adapter, or by a fixture. No game object belongs to this interface.
class InputEmitter {
  public:
    virtual ~InputEmitter() = default;
    virtual void move(InputPosition position) = 0;
    virtual void button(InputButton button, bool down) = 0;
    virtual void wheel(int32_t direction) = 0;
};

enum class InputSequenceState { Running, Releasing, Succeeded, Aborted };

// One admitted finite task. Call only from mutually exclusive game-owned input/frontend phases.
class InputSequence {
    std::vector<InputStep> steps;
    std::vector<InputButton> held;
    size_t nextStep{}, completedSteps{};
    size_t nextMotion{};
    uint32_t remaining{};
    uint64_t evaluatedTicks{};
    std::optional<uint64_t> previousTick, preparedTick;
    InputSequenceState stateValue = InputSequenceState::Running;
    std::string reasonValue;

    void release(InputEmitter &emitter);
    void fail(const char *message);

  public:
    // Validate before cancelling a previous task when admitting a replacement.
    static void validate(const std::vector<InputStep> &steps);
    explicit InputSequence(std::vector<InputStep> steps);

    void beforeTick(uint64_t tick, InputEmitter &emitter);
    void afterTick(uint64_t tick);
    void cancel(const char *reason);
    void cleanup(InputEmitter &emitter);

    InputSequenceState state() const;
    const std::string &reason() const;
    uint64_t ticks() const;
    size_t completed() const;
    bool hasHeldInput() const;
};
