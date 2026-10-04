#pragma once
#include "bridge.h"
#include "input_sequence.h"
#include <array>
#include <cstddef>

// Filled from validated target debug metadata. These are adapter fields, not game object layouts.
using InputEventLayout = FmInputEventLayout;

struct InputEventFunctions {
    void *(*construct)(void *, double, int);
    void *(*destroy)(void *, unsigned);
    uint32_t (*ticks)();
    void (*update)(void *, const void *);
    int (*process)(const void *, bool);
    void (*postUpdate)(void *, const void *);
};

struct GameInputContext {
    void *game, *source, *player, *map, *state;
    uint64_t tick;
};

InputEventFunctions gameInputFunctions(const Symbols &symbols);
GameInputContext gameInputContext(const Symbols &symbols);
void *gameInputState(const Symbols &symbols);

struct InputEventProgress {
    bool started{}, completed{};
};

class GameInputEvents;

// Outlives the borrowed phase adapter. A failed release retains its progress and must never be replayed.
class InputEventButtons {
    friend class GameInputEvents;

    struct Entry {
        InputButton button{};
        void *state{};
        InputEventProgress press, release;
    };

    std::array<Entry, FM_INPUT_ENTRIES> entries{};

  public:
    bool active() const;
    void release(GameInputEvents &events);
};

// The receiver is borrowed only for one mutually exclusive input/frontend phase.
// The caller owns local-player/world validation and must not retain this adapter between phases.
class GameInputEvents final : public InputEmitter {
    const InputEventLayout &layout;
    const InputEventFunctions &functions;
    InputEventButtons &buttons;
    void *state;
    bool route;

    void dispatch(int type, std::optional<uint32_t> key, std::optional<InputPosition> position,
                  std::optional<uint32_t> button, int32_t wheel = 0, InputEventProgress *progress = nullptr);

  public:
    GameInputEvents(const InputEventLayout &layout, const InputEventFunctions &functions, InputEventButtons &buttons,
                    void *state, bool route = true);
    void move(InputPosition position) override;
    // ENTER may change the world before MOVE. Revalidate the caller's ownership between native callbacks.
    void move(InputPosition position, void (*validate)(void *), void *owner);
    void button(InputButton button, bool down) override;
    void wheel(int32_t direction) override;
};
