#pragma once
#include "ui_capture.h"
#include "ui_modal.h"

// Entries, event types and the click flag require matching native ABI/construction evidence.
typedef struct FmLinuxMouseGestureLayout {
    FmLinuxMemberFlag clickOnDown;
    uint32_t enterType;
    uint32_t downType;
    uint32_t clickType;
    uint32_t upType;
    uint32_t leaveType;
    uint32_t previousTarget;
    uint32_t widgetTargetable;
} FmLinuxMouseGestureLayout;

// Fixed adapter configuration. Button order follows the public action contract: left, right, middle.
typedef struct FmLinuxMouseGestureConfig {
    FmLinuxMouseEventLayout event;
    FmLinuxInputClockLayout clock;
    FmLinuxCaptureLayout capture;
    FmLinuxModalLayout modal;
    FmLinuxMouseGestureLayout gesture;
    uint64_t enter;
    uint64_t down;
    uint64_t click;
    uint64_t up;
    uint64_t leave;
    uint16_t buttons[3];
} FmLinuxMouseGestureConfig;

#ifdef __cplusplus
struct UiMouseFunctions {
    void (*enter)(void *, const void *) = nullptr;
    void (*down)(void *, const void *) = nullptr;
    void (*click)(void *, const void *) = nullptr;
    void (*up)(void *, const void *) = nullptr;
    void (*leave)(void *, const void *) = nullptr;
};

struct UiGestureContext {
    uintptr_t guiInstance;
    const FmLinuxUiLayout &ui;
    const FmLinuxMouseEventLayout &event;
    const FmLinuxInputClockLayout &clock;
    const FmLinuxCaptureLayout &capture;
    const FmLinuxMouseGestureLayout &gesture;
    UiMouseFunctions functions;
    const TargetReference *previous = nullptr;
    UiTargetReference *captureRecipient = nullptr;
    UiTargetReference *finalCaptureRecipient = nullptr;
};

// Pointer-free dispatch progress. Requests and borrowed targets are kept separately.
struct UiMouseProgress {
    bool started = false;
    bool finished = false;
    bool upPending = false;
    bool leavePending = false;
    bool clickSkipped = false;
    bool leaveCleanupPending = false;
    int failure = 0;
    int32_t x = 0;
    int32_t y = 0;
    int64_t absoluteX = 0;
    int64_t absoluteY = 0;
    UiMouseDispatch enter;
    UiMouseDispatch down;
    UiMouseDispatch click;
    UiMouseDispatch up;
    UiMouseDispatch leave;
};

// Callback-local only. A failed cleanup retains pointer-free progress and owned references for later reacquisition.
struct UiMouseGesture : UiMouseProgress {
    UiTarget target;
    UiMouseRequest request;
    uintptr_t released = 0;
    UiCapture capture;
    UiCapture finalCapture;
};

bool validMouseGesture(const UiGestureContext &context);
// The caller selects and admits the target, and owns mirrored button/modifier state through gesture cleanup.
// No cancellation or IPC boundary occurs between these synchronous callbacks.
int runUiMouseGesture(const UiGestureContext &context, const UiTarget &target, const UiMouseRequest &request,
                      UiMouseGesture &state);
int finishUiMouseGesture(const UiGestureContext &context, UiMouseGesture &state);
#endif
