#pragma once
#include "keyboard_pump.h"
#include "pointer_pump.h"
#include <array>
#include <optional>

// Native execution/cleanup for one bounded InputSequence. Scheduling, cancellation and task ownership stay
// outside this adapter. Slots survive a throwing down/up until their native release obligation is resolved.
class PumpInputEmitter final : public InputEmitter {
  public:
    PumpInputEmitter(EventPump &pump, const KeyboardPumpConfig &keyboard, const PointerPumpConfig &pointer,
                     EventPump::Pump entry, void *context, PointerPump::Guard guard, void *guardContext,
                     pid_t evaluationThread = 0);
    void move(InputPosition position) override;
    void button(InputButton button, bool down) override;
    void wheel(int32_t direction) override;
    bool owned() const;

  private:
    struct Slot {
        InputButton button{};
        std::optional<KeyboardPumpKey> key;
        std::optional<PointerPumpButton> mouse;
    };

    EventPump &pump_;
    KeyboardPumpConfig keyboard_;
    PointerPumpConfig pointer_;
    EventPump::Pump entry_;
    void *context_;
    const pid_t evaluationThread_;
    PointerPump motion_;
    std::array<Slot, 8> slots_;
};
