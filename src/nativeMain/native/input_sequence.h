#pragma once
#include "input_timeline_wire.h"
#include <array>
#include <cstdint>
#include <optional>
#include <stdexcept>
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

struct InputPoint {
    double x, y;
};

struct InputInterval {
    uint32_t first, last;
};
enum class InputKind : uint32_t { Keyboard, MouseButton, Position, Motion, Wheel };

struct InputEntry {
    InputKind kind;
    uint32_t code{};
    std::vector<InputInterval> intervals;
    bool world{}, tileCenters{};
    InputPoint from{}, to{};
    uint32_t perPointTicks{};
};

// Implemented by the game adapter, or by a fixture. No game object belongs to this interface.
class InputEmitter {
  public:
    virtual ~InputEmitter() = default;
    virtual void move(InputPosition position) = 0;

    virtual void moveWorld(InputPoint) {
        throw std::logic_error("World coordinate adapter is unavailable");
    }

    virtual void button(InputButton button, bool down) = 0;
    virtual void wheel(int32_t direction) = 0;
};
enum class InputSequenceState { Running, Releasing, Succeeded, Aborted };

// One admitted, bounded timeline. Tick zero is the first eligible local input evaluation.
class InputSequence {
    struct Cursor {
        std::array<bool, FM_INPUT_INTERVALS> active{};
        size_t finished{};
        bool completed{};
    };

    struct Held {
        InputButton button;
        size_t owners;
    };

    std::vector<InputEntry> entries;
    std::vector<Cursor> cursors;
    std::vector<Held> held;
    size_t completedEntries{};
    uint64_t evaluatedTicks{}, lastTick{};
    std::optional<uint64_t> previousTick, preparedTick;
    InputSequenceState stateValue = InputSequenceState::Running;
    std::string reasonValue;

    void release(InputEmitter &emitter);
    void fail(const char *message);
    void acquire(InputButton button, InputEmitter &emitter);
    void relinquish(InputButton button, InputEmitter &emitter);

  public:
    static void validate(const std::vector<InputEntry> &entries);
    static std::vector<InputEntry> decode(const FmInputTimelineEntry *rows, uint32_t count);
    explicit InputSequence(std::vector<InputEntry> entries);
    void beforeTick(uint64_t tick, InputEmitter &emitter);
    void afterTick(uint64_t tick, InputEmitter &emitter);
    void cancel(const char *reason);
    void cleanup(InputEmitter &emitter);
    InputSequenceState state() const;
    const std::string &reason() const;
    uint64_t ticks() const;
    size_t completed() const;
    bool hasHeldInput() const;
};
