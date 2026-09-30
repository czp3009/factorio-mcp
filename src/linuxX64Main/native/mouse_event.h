#pragma once
#include "ui_snapshot.h"
#include "input_clock.h"

#define FM_LINUX_MOUSE_EVENT_BYTES 256

// All fields and supported construction defaults must come from matching native metadata.
typedef struct FmLinuxMouseEventLayout {
    uint32_t size;
    uint32_t x;
    uint32_t y;
    uint32_t source;
    uint32_t wheel;
    uint32_t button;
    uint32_t type;
    uint32_t time;
    uint32_t alt;
    uint32_t control;
    uint32_t shift;
    uint32_t hasPrevious;
    uint32_t previous;
} FmLinuxMouseEventLayout;

#ifdef __cplusplus
class TargetReference;

struct UiMouseParameters {
    uint32_t type = 0;
    uint16_t button = 0;
    bool alt = false;
    bool control = false;
    bool shift = false;
    double x = 0.5;
    double y = 0.5;
};

struct UiMouseRequest : UiMouseParameters {
    uintptr_t previous = 0;
    const TargetReference *previousReference = nullptr;
    uint32_t previousTargetable = 0;
};

struct UiMouseDispatch {
    bool entered = false;
    bool returned = false;
    int targetStatus = 0;
};

bool validMouseEventLayout(const FmLinuxMouseEventLayout &layout);
// One dispatch, at the verified safe point. The caller must verify this entry's ABI, the clock's code/data and the
// selected event's zero defaults. Input-state mirroring, finite gesture sequencing and capture cleanup remain caller obligations.
int dispatchUiMouse(uintptr_t guiInstance, const FmLinuxUiLayout &ui, const FmLinuxMouseEventLayout &event,
                    const FmLinuxInputClockLayout &clock,
                    void (*entry)(void *, const void *), const UiTarget &target, const UiMouseRequest &request,
                    UiMouseDispatch &output);
// Local pixels may be outside the recipient when capture moves during a finite gesture.
int dispatchUiMouseAt(uintptr_t guiInstance, const FmLinuxUiLayout &ui, const FmLinuxMouseEventLayout &event,
                      const FmLinuxInputClockLayout &clock, void (*entry)(void *, const void *),
                      const UiTarget &target, const UiMouseRequest &request, int32_t x, int32_t y,
                      UiMouseDispatch &output);
#endif
