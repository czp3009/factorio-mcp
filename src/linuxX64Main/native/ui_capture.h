#pragma once
#include "mouse_event.h"
#include "targeter.h"

#define FM_LINUX_CAPTURE_RESETS 8

typedef struct FmLinuxCaptureReset {
    uint32_t offset;
    uint32_t width;
    uint64_t value;
} FmLinuxCaptureReset;

typedef struct FmLinuxCaptureLayout {
    uint32_t guiSize;
    uint32_t widgetSize;
    uint32_t widgetTargetable;
    uint32_t targeterMember;
    FmLinuxTargeterLayout targeter;
    uint32_t resetCount;
    FmLinuxCaptureReset resets[FM_LINUX_CAPTURE_RESETS];
} FmLinuxCaptureLayout;

#ifdef __cplusplus
// Pointer-free progress can survive a callback; borrowed objects must be reacquired separately.
struct UiCaptureProgress {
    bool cleanupStarted = false;
    bool upAttempted = false;
    bool finished = false;
    uint32_t resetWritten = 0;
    int failure = 0;
    UiMouseDispatch up;
};

// Borrowed within one safe point only. Keep progress and owned references on failure, never these raw addresses.
struct UiCapture : UiCaptureProgress {
    uintptr_t gui = 0;
    uintptr_t root = 0;
    uintptr_t initial = 0;
    uintptr_t released = 0;
    const UiTargetReference *rootReference = nullptr;
    const TargetReference *initialReference = nullptr;
    UiTargetReference *recipientReference = nullptr;
};

bool validCaptureLayout(const FmLinuxCaptureLayout &capture, const FmLinuxUiLayout &ui);
int beginUiCapture(uintptr_t guiInstance, const FmLinuxUiLayout &ui, const FmLinuxCaptureLayout &layout,
                   const UiTarget &target, UiCapture &output);
// Dispatches at most one up, then unlinks the current transferred capture and resets its native GUI bookkeeping.
// The absolute point is the original gesture point, independent of the original widget's subsequent lifetime.
int finishUiCapture(uintptr_t guiInstance, const FmLinuxUiLayout &ui, const FmLinuxCaptureLayout &layout,
                    const FmLinuxMouseEventLayout &event, const FmLinuxInputClockLayout &clock,
                    void (*up)(void *, const void *), const UiMouseRequest &request,
                    int64_t absoluteX, int64_t absoluteY, uintptr_t alreadyReleased, UiCapture &state);
#endif
