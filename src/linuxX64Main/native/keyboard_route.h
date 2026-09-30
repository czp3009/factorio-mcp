#pragma once
#include "event_route.h"
#include "key_state.h"
#include "keyboard_event.h"
#include <array>
#include <optional>

struct KeyboardRouteConfig {
    FmLinuxMouseStateLayout owner;
    FmLinuxKeyStateLayout keys;
    FmLinuxKeyboardEventLayout event;
    FmLinuxEventClock clock;
    EventUpdateOrder pressOrder;
    EventUpdateOrder releaseOrder;
};

// One key through the verified native source/GUI/state route. The task owner serializes the two admitted
// threads at verified game phases, proves the event consumers/ABIs and retains input-service lifetime.
// The release resolver must support cleanup independently of the task's new-input world guard.
class KeyboardRouteKey {
public:
    KeyboardRouteKey(const KeyboardRouteConfig &config, const EventRouteFunctions &functions,
                     pid_t inputThread, pid_t cleanupThread, EventRoute::Resolve pressResolver,
                     EventRoute::Resolve releaseResolver, void *context);
    KeyboardRouteKey(const KeyboardRouteKey &) = delete;
    KeyboardRouteKey &operator=(const KeyboardRouteKey &) = delete;
    int press(uint32_t code);
    int release();
    bool owned() const { return owned_; }
    int failure() const { return failure_; }
    const EventRouteProgress &pressProgress() const { return press_; }
    const EventRouteProgress &releaseProgress() const { return release_; }

private:
    const KeyboardRouteConfig config_;
    const EventRouteFunctions functions_;
    const pid_t inputThread_, cleanupThread_;
    const EventRoute::Resolve pressResolver_, releaseResolver_;
    void *const context_;
    bool started_ = false, dispatching_ = false, down_ = false, owned_ = false;
    int failure_ = 0;
    uint32_t code_ = 0;
    uintptr_t global_ = 0, state_ = 0;
    EventRouteProgress press_{}, release_{};

    int record(int error);
    int service();
    int current(KeyStateValue &value);
    int dispatch(bool down, EventRouteProgress &progress);
    static int resolve(EventRouteStage stage, void *context, void *&receiver) noexcept;
};

// Fixed storage for the sequence's at-most-eight held buttons. The task owner provides the same serialized
// phases as KeyboardRouteKey. An error on down is reported once; successful cleanup consumes its ownership
// even when the key retains that historical error. No entry is replaced while its native release is uncertain.
class KeyboardRouteKeys {
public:
    KeyboardRouteKeys(const KeyboardRouteConfig &config, const EventRouteFunctions &functions,
                      pid_t inputThread, pid_t cleanupThread, EventRoute::Resolve pressResolver,
                      EventRoute::Resolve releaseResolver, void *context);
    int button(uint32_t code, bool down);
    bool owned() const;

private:
    struct Slot {
        uint32_t code = 0;
        std::optional<KeyboardRouteKey> key;
    };
    std::array<Slot, 8> slots_;
    const KeyboardRouteConfig config_;
    const EventRouteFunctions functions_;
    const pid_t inputThread_, cleanupThread_;
    const EventRoute::Resolve pressResolver_, releaseResolver_;
    void *const context_;
    bool dispatching_ = false;
};
