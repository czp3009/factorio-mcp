#pragma once
#include "bridge.h"
#include "input_sequence.h"
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

// The receiver is borrowed only for one mutually exclusive input/frontend phase.
// The caller owns local-player/world validation and must not retain this adapter between phases.
class GameInputEvents final : public InputEmitter {
    const InputEventLayout &layout;
    const InputEventFunctions &functions;
    void *state;
    bool route;

    void dispatch(int type, std::optional<uint32_t> key, std::optional<InputPosition> position,
                  std::optional<uint32_t> button, int32_t wheel = 0);

  public:
    GameInputEvents(const InputEventLayout &layout, const InputEventFunctions &functions, void *state,
                    bool route = true);
    void move(InputPosition position) override;
    void button(InputButton button, bool down) override;
    void wheel(int32_t direction) override;
};
