#include "ui_command.h"
#include "memory_read.h"
#include <cerrno>

int UiMouseCommand::record(int error) {
    if (error && !failure_)
        failure_ = error;
    return failure_;
}

UiGestureContext UiMouseCommand::bind(const UiGestureContext &context) {
    auto bound = context;
    bound.initialCapture = &initial_;
    bound.previous = &previous_;
    bound.captureRecipient = &recipient_;
    bound.finalCaptureRecipient = &finalRecipient_;
    return bound;
}

int UiMouseCommand::releaseReferences() {
    record(pendingCapture_.release());
    record(recipient_.release());
    record(finalRecipient_.release());
    record(previous_.release());
    record(initial_.release());
    record(target_.release());
    finished_ = !pendingCapture_.owned() && !recipient_.owned() && !finalRecipient_.owned() && !previous_.owned() &&
        !initial_.owned() && !target_.owned();
    return failure_;
}

void UiMouseCommand::save(const UiMouseGesture &state) {
    progress_ = static_cast<const UiMouseProgress &>(state);
    captureProgress_ = static_cast<const UiCaptureProgress &>(state.capture);
    finalCaptureProgress_ = static_cast<const UiCaptureProgress &>(state.finalCapture);
    releasedOriginal_ = state.released && state.released == state.target.widget;
    record(state.failure);
}

int UiMouseCommand::rememberCapture(const UiGestureContext &context) {
    captureKnown_ = false;
    uintptr_t gui, root;
    const int rootError = target_.borrowRoot(context.guiInstance, context.ui, gui, root);
    if (rootError == ESTALE) {
        progress_.upPending = false;
        progress_.leavePending = false;
        captureProgress_.finished = true;
        finalCaptureProgress_.finished = true;
        progress_.finished = true;
        return releaseReferences();
    }
    if (rootError)
        return record(rootError);
    TargeterLinks links;
    if (const int error = readTargeter(gui + context.capture.targeterMember, context.capture.targeter, links))
        return record(error);
    if (pendingCapture_.owned()) {
        uintptr_t previous;
        if (const int error = pendingCapture_.borrow(previous))
            return record(error);
        if (previous == links.target) {
            captureKnown_ = true;
            return failure_;
        }
        if (const int error = pendingCapture_.release())
            return record(error);
    }
    if (links.target) {
        const int error = pendingCapture_.attach(links.target, context.capture.targeter);
        record(error);
        uintptr_t borrowed;
        if (pendingCapture_.borrow(borrowed) || borrowed != links.target)
            return record(EPROTO);
    }
    captureKnown_ = true;
    return failure_;
}

int UiMouseCommand::start(const UiGestureContext &context, const UiTarget &target, const UiMouseRequest &request) {
    if (started_)
        return EALREADY;
    started_ = true;
    parameters_ = static_cast<const UiMouseParameters &>(request);
    if (!validMouseGesture(context) || request.previous || request.previousReference) {
        record(EINVAL);
        return releaseReferences();
    }
    int error = target_.attach(context.guiInstance, context.ui, context.capture, target);
    TargeterLinks initial;
    if (!error)
        error = readTargeter(target.gui + context.capture.targeterMember, context.capture.targeter, initial);
    if (!error && initial.target)
        error = initial_.attach(initial.target, context.capture.targeter);
    uintptr_t previous = 0;
    if (!error && !fm::read(target.gui + context.gesture.previousTarget, previous))
        error = EFAULT;
    if (!error && previous) {
        if (previous < context.gesture.widgetTargetable)
            error = EFAULT;
        else
            error = liveUiTarget(context.guiInstance, context.ui,
                {target.gui, target.root, previous - context.gesture.widgetTargetable, {}});
        if (!error)
            error = previous_.attach(previous, context.capture.targeter);
    }
    UiTarget selected;
    if (!error)
        error = target_.borrow(context.guiInstance, context.ui, selected);
    if (error) {
        record(error);
        return releaseReferences();
    }
    selected.bounds = target.bounds;
    UiMouseGesture state;
    record(runUiMouseGesture(bind(context), selected, request, state));
    save(state);
    if (!progress_.started || progress_.finished)
        return releaseReferences();
    return rememberCapture(context);
}

int UiMouseCommand::finish(const UiGestureContext &context) {
    if (!started_)
        return EINVAL;
    if (finished_)
        return failure_;
    if (!validMouseGesture(context))
        return record(EINVAL);
    if (!progress_.started || progress_.finished)
        return releaseReferences();
    uintptr_t gui, root;
    const int rootError = target_.borrowRoot(context.guiInstance, context.ui, gui, root);
    if (rootError == ESTALE) {
        progress_.upPending = false;
        progress_.leavePending = false;
        captureProgress_.finished = true;
        finalCaptureProgress_.finished = true;
        progress_.finished = true;
        return releaseReferences();
    }
    if (rootError)
        return record(rootError);
    // A different capture between callbacks is not proven to belong to this command. Retain ownership and refuse
    // to release it or overwrite our progress. An invalidated root can still discharge these obligations later.
    if (!captureKnown_)
        return record(EPROTO);
    uintptr_t expectedCapture;
    if (const int error = pendingCapture_.borrow(expectedCapture))
        return record(error);
    TargeterLinks currentCapture;
    if (const int error = readTargeter(gui + context.capture.targeterMember, context.capture.targeter, currentCapture))
        return record(error);
    if (currentCapture.target != expectedCapture)
        return record(ESTALE);

    UiMouseGesture state;
    static_cast<UiMouseProgress &>(state) = progress_;
    static_cast<UiMouseParameters &>(state.request) = parameters_;
    const int targetError = target_.borrow(context.guiInstance, context.ui, state.target);
    if (targetError == ENOENT) {
        state.upPending = false;
        state.leavePending = false;
        state.target.gui = gui;
        state.target.root = root;
    } else if (targetError)
        return record(targetError);
    state.released = releasedOriginal_ ? state.target.widget : 0;
    static_cast<UiCaptureProgress &>(state.capture) = captureProgress_;
    state.capture.gui = gui;
    state.capture.root = root;
    state.capture.rootReference = &target_;
    state.capture.initialReference = &initial_;
    state.capture.recipientReference = &recipient_;
    static_cast<UiCaptureProgress &>(state.finalCapture) = finalCaptureProgress_;
    state.finalCapture.gui = gui;
    state.finalCapture.root = root;
    state.finalCapture.rootReference = &target_;
    state.finalCapture.initialReference = &initial_;
    state.finalCapture.recipientReference = &finalRecipient_;
    record(finishUiMouseGesture(bind(context), state));
    save(state);
    if (progress_.finished)
        return releaseReferences();
    return rememberCapture(context);
}
