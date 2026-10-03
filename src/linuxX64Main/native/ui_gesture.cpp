#include "ui_gesture.h"
#include "memory_read.h"
#include <cerrno>
#include <cmath>

namespace {
int record(UiMouseGesture &state, int error) {
    if (error && !state.failure)
        state.failure = error;
    return state.failure;
}

int dispatch(const UiGestureContext &context, UiMouseGesture &state, void (*entry)(void *, const void *), uint32_t type,
             UiMouseDispatch &progress) {
    auto request = state.request;
    request.type = type;
    const int error = dispatchUiMouseAt(context.guiInstance, context.ui, context.event, context.clock, entry,
                                        state.target, request, state.x, state.y, progress);
    // Disappearance after admission is a normal outcome. A throwing callback still retains its own error.
    return (error == ENOENT || error == ESTALE) && error == progress.targetStatus ? 0 : error;
}
} // namespace

bool validMouseGesture(const UiGestureContext &context) {
    const auto &flag = context.gesture.clickOnDown;
    return validUiLayout(context.ui) && validMouseEventLayout(context.event) && context.event.hasPrevious &&
           validInputClockLayout(context.clock) && context.clock.guiSize == context.ui.guiSize &&
           validCaptureLayout(context.capture, context.ui) &&
           fm::member(context.gesture.previousTarget, sizeof(uintptr_t), context.ui.guiSize) &&
           fm::member(context.gesture.widgetTargetable, sizeof(uintptr_t), context.ui.widgetSize) &&
           (flag.width == 1 || flag.width == 2 || flag.width == 4 || flag.width == 8) &&
           fm::member(flag.offset, flag.width, context.ui.widgetSize) && flag.mask && !(flag.mask & (flag.mask - 1)) &&
           flag.shift < flag.width * 8 && flag.mask == (uint64_t{1} << flag.shift) && context.functions.enter &&
           context.functions.down && context.functions.click && context.functions.up && context.functions.leave;
}

int finishUiMouseGesture(const UiGestureContext &context, UiMouseGesture &state) {
    if (!validMouseGesture(context) || !state.started)
        return EINVAL;
    if (state.finished)
        return state.failure;
    if (state.upPending) {
        record(state, dispatch(context, state, context.functions.up, context.gesture.upType, state.up));
        if (state.up.entered) {
            state.released = state.target.widget;
            state.upPending = false;
        } else if (state.up.targetStatus == ENOENT || state.up.targetStatus == ESTALE)
            state.upPending = false;
    }
    auto release = state.request;
    release.type = context.gesture.upType;
    record(state, finishUiCapture(context.guiInstance, context.ui, context.capture, context.event, context.clock,
                                  context.functions.up, release, state.absoluteX, state.absoluteY, state.released,
                                  state.capture));
    // Capture cleanup may have delivered the only up after the original up failed before entry.
    if (state.capture.released == state.target.widget) {
        state.released = state.target.widget;
        state.upPending = false;
    }
    if (state.leavePending && state.capture.finished) {
        record(state, dispatch(context, state, context.functions.leave, context.gesture.leaveType, state.leave));
        state.leaveCleanupPending = state.leave.entered;
        if (state.leave.entered || state.leave.targetStatus == ENOENT || state.leave.targetStatus == ESTALE)
            state.leavePending = false;
    }
    if (state.capture.finished && state.leaveCleanupPending) {
        auto released = state.released;
        if (state.target.reference && liveUiTarget(context.guiInstance, context.ui, state.target))
            released = 0;
        record(state, finishUiCapture(context.guiInstance, context.ui, context.capture, context.event, context.clock,
                                      context.functions.up, release, state.absoluteX, state.absoluteY, released,
                                      state.finalCapture));
    }
    state.finished = !state.upPending && !state.leavePending && state.capture.finished &&
                     (!state.leaveCleanupPending || state.finalCapture.finished);
    return state.failure;
}

int runUiMouseGesture(const UiGestureContext &context, const UiTarget &target, const UiMouseRequest &request,
                      UiMouseGesture &state) {
    if (state.started)
        return EALREADY;
    if (!validMouseGesture(context) || !request.button || request.previous || !std::isfinite(request.x) ||
        !std::isfinite(request.y) || request.x < 0 || request.x > 1 || request.y < 0 || request.y > 1 ||
        target.bounds.width <= 0 || target.bounds.height <= 0)
        return EINVAL;
    UiCapture capture;
    if (const int error = beginUiCapture(context.guiInstance, context.ui, context.capture, target, capture))
        return error;
    capture.rootReference = target.reference;
    capture.recipientReference = context.captureRecipient;
    uintptr_t previous;
    if (!fm::read(target.gui + context.gesture.previousTarget, previous))
        return EFAULT;
    if (context.previous) {
        uintptr_t expected;
        if (const int error = context.previous->borrow(expected))
            return error;
        if (expected != previous)
            return ESTALE;
    }
    if (previous) {
        if (previous < context.gesture.widgetTargetable)
            return EFAULT;
        previous -= context.gesture.widgetTargetable;
        if (const int error = liveUiTarget(context.guiInstance, context.ui, {target.gui, target.root, previous, {}}))
            return error;
    }
    state = {};
    state.started = true;
    state.target = target;
    state.request = request;
    state.request.previous = previous;
    state.request.previousReference = context.previous;
    state.request.previousTargetable = context.gesture.widgetTargetable;
    state.capture = capture;
    state.finalCapture = capture;
    state.finalCapture.recipientReference = context.finalCaptureRecipient;
    state.x = static_cast<int32_t>((target.bounds.width - 1) * request.x);
    state.y = static_cast<int32_t>((target.bounds.height - 1) * request.y);
    state.absoluteX = int64_t{target.bounds.x} + state.x;
    state.absoluteY = int64_t{target.bounds.y} + state.y;
    record(state, dispatch(context, state, context.functions.enter, context.gesture.enterType, state.enter));
    state.request.previous = 0;
    state.request.previousReference = nullptr;
    state.request.previousTargetable = 0;
    state.leavePending = state.enter.entered;
    if (!state.failure && state.enter.returned && !state.enter.targetStatus) {
        record(state, dispatch(context, state, context.functions.down, context.gesture.downType, state.down));
        state.upPending = state.down.entered;
    }
    if (!state.failure && state.down.returned && !state.down.targetStatus) {
        const auto &flag = context.gesture.clickOnDown;
        uint64_t value = 0;
        if (!fm::readBytes(target.widget + flag.offset, &value, flag.width))
            record(state, EFAULT);
        else if (value & flag.mask)
            state.clickSkipped = true;
        else
            record(state, dispatch(context, state, context.functions.click, context.gesture.clickType, state.click));
    }
    return finishUiMouseGesture(context, state);
}
