#pragma once
#include "event_pump.h"
#include "mouse_input_event.h"
#include "../../nativeMain/native/input_sequence.h"

struct PointerPumpConfig {
    FmLinuxPollHookConfig site;
    FmLinuxMouseStateLayout owner;
    FmLinuxPointerStateLayout state;
    FmLinuxPointerEventLayout event;
    FmLinuxEventClock clock;
};

bool validPointerPumpConfig(const PointerPumpConfig &config);

// One logical mouse button with native route progress. Addresses are comparison-only tokens, never borrowed
// references. All calls require a verified mutually exclusive frontend/evaluation phase, as for KeyboardPumpKey.
class PointerPumpButton {
  public:
    PointerPumpButton(EventPump &pump, const PointerPumpConfig &config, EventPump::Pump entry, void *context,
                      pid_t evaluationThread = 0);
    PointerPumpButton(const PointerPumpButton &) = delete;
    PointerPumpButton &operator=(const PointerPumpButton &) = delete;
    int press(uint32_t button);
    int release();

    bool owned() const {
        return owned_;
    }

    int failure() const {
        return failure_;
    }

    const EventPumpProgress &pressProgress() const {
        return press_;
    }

    const EventPumpProgress &releaseProgress() const {
        return release_;
    }

  private:
    EventPump &pump_;
    PointerPumpConfig config_;
    EventPump::Pump entry_;
    void *context_;
    const pid_t evaluationThread_;
    bool started_ = false, owned_ = false, dispatching_ = false, writingDown_ = false;
    int failure_ = 0;
    uint32_t button_ = 0, mask_ = 0;
    uintptr_t global_ = 0, state_ = 0;
    EventPumpProgress press_, release_;
    int record(int error);
    int current(InputStateObjects &objects, PointerStateValue &value);
    static int write(void *event, size_t capacity, void *context) noexcept;
};

struct PointerMoveProgress {
    EventPumpProgress enter;
    EventPumpProgress move;
};

// Finite motion/wheel dispatch. A mandatory ownership guard separates ENTER and MOVE and is rechecked after
// the native clock callback. The caller advances its finite operation before dispatch and never replays it.
class PointerPump {
  public:
    using Guard = int (*)(void *);
    PointerPump(EventPump &pump, const PointerPumpConfig &config, EventPump::Pump entry, void *context, Guard guard,
                void *guardContext, pid_t evaluationThread = 0);
    PointerPump(const PointerPump &) = delete;
    PointerPump &operator=(const PointerPump &) = delete;
    int move(InputPosition position, PointerMoveProgress &progress);
    int wheel(int32_t direction, EventPumpProgress &progress);

  private:
    EventPump &pump_;
    PointerPumpConfig config_;
    EventPump::Pump entry_;
    void *context_;
    Guard guard_;
    void *guardContext_;
    const pid_t evaluationThread_;
    bool dispatching_ = false;
    uintptr_t global_ = 0, state_ = 0;
    PointerEventRequest request_;
    int guarded() noexcept;
    int begin(PointerStateValue &value);
    int current(PointerStateValue &value);
    static int write(void *event, size_t capacity, void *context) noexcept;
};
