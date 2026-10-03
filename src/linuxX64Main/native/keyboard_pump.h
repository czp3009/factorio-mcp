#pragma once
#include "event_pump.h"
#include "key_state.h"
#include "keyboard_event.h"

struct KeyboardPumpConfig {
    FmLinuxPollHookConfig site;
    FmLinuxMouseStateLayout owner;
    FmLinuxKeyStateLayout keys;
    FmLinuxKeyboardEventLayout event;
    FmLinuxEventClock clock;
};

// One key routed through the normal native event pump, not just mirrored into InputState. All methods run
// at verified mutually exclusive evaluation/frontend phases. The caller must prove the supplied pump boundary and
// the process-wide input service's lifetime. Stored addresses are comparison tokens, never borrowed objects.
class KeyboardPumpKey {
  public:
    KeyboardPumpKey(EventPump &pump, const KeyboardPumpConfig &config, EventPump::Pump entry, void *context,
                    pid_t evaluationThread = 0);
    KeyboardPumpKey(const KeyboardPumpKey &) = delete;
    KeyboardPumpKey &operator=(const KeyboardPumpKey &) = delete;
    int press(uint32_t code);
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
    KeyboardPumpConfig config_;
    EventPump::Pump entry_;
    void *context_;
    const pid_t evaluationThread_;
    bool started_ = false;
    bool owned_ = false;
    bool dispatching_ = false;
    bool writingDown_ = false;
    int failure_ = 0;
    uint32_t code_ = 0;
    uintptr_t global_ = 0;
    uintptr_t state_ = 0;
    EventPumpProgress press_;
    EventPumpProgress release_;

    int record(int error);
    int current(KeyStateValue &value);
    static int write(void *event, size_t capacity, void *context) noexcept;
};
