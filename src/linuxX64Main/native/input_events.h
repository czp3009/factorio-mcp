#pragma once
#include <stdint.h>

#define FM_LINUX_INPUT_EVENT_BYTES 4096
#define FM_LINUX_MOUSE_BUTTONS 3

// Adapter metadata only. Every game field and entry must be resolved and verified against the live image.
typedef struct FmLinuxMouseStateLayout {
    uint64_t global;
    uint32_t globalSize;
    uint32_t stateMember;
    uint32_t stateSize;
    uint32_t heldMask;
    uint32_t eventSize;
    uint32_t eventType;
    uint32_t eventTime;
    uint32_t eventCode;
    uint32_t press;
    uint32_t release;
    uint32_t codes[FM_LINUX_MOUSE_BUTTONS];
    uint32_t masks[FM_LINUX_MOUSE_BUTTONS];
} FmLinuxMouseStateLayout;

typedef struct FmLinuxMouseStateConfig {
    FmLinuxMouseStateLayout layout;
    uint64_t update;
    uint64_t postUpdate;
} FmLinuxMouseStateConfig;

#ifdef __cplusplus
struct InputStateObjects {
    uintptr_t global = 0;
    uintptr_t state = 0;
    uint32_t held = 0;
};

struct InputStateFunctions {
    void (*update)(void *, const void *);
    void (*postUpdate)(void *, const void *);
};

struct InputDispatch {
    bool updateEntered = false;
    bool updateReturned = false;
    bool postEntered = false;
    bool postReturned = false;
};

bool validMouseStateLayout(const FmLinuxMouseStateLayout &layout);
// Same frontend callback only. These references must never be retained across frames or world changes.
int readInputState(const FmLinuxMouseStateLayout &layout, InputStateObjects &output);
// One edge only, with no routing, retry, effect polling or ownership policy. The caller owns cleanup after admission,
// including uncertain/throwing updates. Cancellation must not bypass an already owned release.
// Before use, verify that selected native cases accept the bounded non-owning scalar event storage initialized here.
int dispatchMouseState(const FmLinuxMouseStateLayout &layout, const InputStateFunctions &functions,
                       const InputStateObjects &objects, uint32_t code, bool pressed, double time, InputDispatch &output);
// Shared native entry boundary. Callers must construct a verified, non-owning scalar event for the selected case.
int dispatchInputStateEvent(const FmLinuxMouseStateLayout &owner, const InputStateFunctions &functions,
                           const InputStateObjects &objects,
                           const unsigned char (&event)[FM_LINUX_INPUT_EVENT_BYTES], InputDispatch &output);

// Tracks one mirrored button in the authoritative process-wide input service. Stored addresses are comparison-only
// identity tokens: every dispatch uses freshly read InputStateObjects, never a retained borrowed pointer.
class MouseButtonOwnership {
public:
    MouseButtonOwnership() = default;
    MouseButtonOwnership(const MouseButtonOwnership &) = delete;
    MouseButtonOwnership &operator=(const MouseButtonOwnership &) = delete;
    MouseButtonOwnership(MouseButtonOwnership &&) = delete;
    MouseButtonOwnership &operator=(MouseButtonOwnership &&) = delete;
    bool owned() const { return owned_; }
    int failure() const { return failure_; }
    const InputDispatch &pressProgress() const { return press_; }
    const InputDispatch &releaseProgress() const { return release_; }
    int press(const FmLinuxMouseStateLayout &layout, const InputStateFunctions &functions, uint32_t code, double time);
    // Reconcile disappearance or an already entered release without requiring a clock or invoking game code.
    int reconcile(const FmLinuxMouseStateLayout &layout);
    int release(const FmLinuxMouseStateLayout &layout, const InputStateFunctions &functions, double time);

private:
    int record(int error);
    bool matches(const InputStateObjects &objects) const;
    bool started_ = false;
    bool owned_ = false;
    int failure_ = 0;
    uintptr_t globalIdentity_ = 0;
    uintptr_t stateIdentity_ = 0;
    uint32_t code_ = 0;
    uint32_t mask_ = 0;
    InputDispatch press_;
    InputDispatch release_;
};
#endif
