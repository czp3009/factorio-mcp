#pragma once
#include "input_events.h"

#define FM_LINUX_KEY_RECORDS 4096

typedef struct FmLinuxKeyboardModifiers {
    uint32_t state;
    // SDL scancodes: left/right shift, control, alt, GUI, mode, num lock, caps lock, scroll lock.
    uint32_t keys[12];
} FmLinuxKeyboardModifiers;

typedef struct FmLinuxKeyStateLayout {
    uint32_t map;
    uint32_t begin;
    uint32_t end;
    uint32_t stride;
    uint32_t key;
    uint32_t held;
    FmLinuxKeyboardModifiers modifiers;
} FmLinuxKeyStateLayout;

typedef struct FmLinuxKeyboardStateConfig {
    FmLinuxKeyStateLayout layout;
    uint32_t eventCode;
    uint32_t eventModifiers;
    uint32_t press;
    uint32_t release;
    // SDL scancodes in control, shift, alt order, tied to the decoded SDK event producer.
    uint32_t codes[3];
} FmLinuxKeyboardStateConfig;

#ifdef __cplusplus
struct KeyStateValue {
    bool present = false;
    bool held = false;
};

bool validKeyStateLayout(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyStateLayout &layout);
bool validKeyboardModifiers(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyboardModifiers &layout);
int readKeyboardModifiers(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyboardModifiers &layout,
                          uintptr_t global, uintptr_t state, uint32_t code, bool down, uint16_t &output);
// Passive, bounded reads only. Reacquires the authoritative service and never calls the allocating native lookup.
// Objects and any record storage remain borrowed within this frontend callback; no key pointer is retained.
int readKeyState(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyStateLayout &layout, int32_t code,
                 InputStateObjects &objects, KeyStateValue &output);

bool validKeyboardStateConfig(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyboardStateConfig &config);
int dispatchKeyState(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyboardStateConfig &config,
                     const InputStateFunctions &functions, const InputStateObjects &objects, uint32_t code,
                     bool pressed, double time, InputDispatch &output);

// One mirrored key. Identity tokens are comparison-only; every read and dispatch reacquires the live service.
class KeyboardKeyOwnership {
  public:
    KeyboardKeyOwnership() = default;
    KeyboardKeyOwnership(const KeyboardKeyOwnership &) = delete;
    KeyboardKeyOwnership &operator=(const KeyboardKeyOwnership &) = delete;

    bool owned() const {
        return owned_;
    }

    int failure() const {
        return failure_;
    }

    const InputDispatch &pressProgress() const {
        return press_;
    }

    const InputDispatch &releaseProgress() const {
        return release_;
    }

    int press(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyboardStateConfig &config,
              const InputStateFunctions &functions, uint32_t code, double time);
    int reconcile(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyboardStateConfig &config);
    int release(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyboardStateConfig &config,
                const InputStateFunctions &functions, double time);

  private:
    int record(int error);
    bool matches(const InputStateObjects &objects) const;
    bool started_ = false;
    bool owned_ = false;
    int failure_ = 0;
    uintptr_t globalIdentity_ = 0;
    uintptr_t stateIdentity_ = 0;
    uint32_t code_ = 0;
    InputDispatch press_;
    InputDispatch release_;
};
#endif
