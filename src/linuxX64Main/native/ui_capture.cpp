#include "ui_capture.h"
#include "ui_reference.h"
#include "memory_read.h"
#include <cerrno>
#include <climits>

namespace {
int current(uintptr_t instance, const FmLinuxUiLayout &layout, const UiCapture &state) {
    uintptr_t gui, root;
    if (state.rootReference) {
        if (const int error = state.rootReference->borrowRoot(instance, layout, gui, root))
            return error;
        return gui == state.gui && root == state.root ? 0 : ESTALE;
    }
    if (!fm::read(instance, gui))
        return EFAULT;
    if (gui != state.gui)
        return ESTALE;
    if (!fm::addressRange(gui, layout.guiSize) || !fm::read(gui + layout.root, root))
        return EFAULT;
    return root == state.root ? 0 : ESTALE;
}

int reset(uintptr_t gui, const FmLinuxCaptureLayout &layout, UiCapture &state) {
    while (state.resetWritten < layout.resetCount) {
        const auto &entry = layout.resets[state.resetWritten];
        // Kernel-mediated writes fail cleanly on an unreadable/read-only region, retaining the unfinished reset.
        iovec local{const_cast<uint64_t *>(&entry.value), entry.width};
        iovec remote{reinterpret_cast<void *>(gui + entry.offset), entry.width};
        if (process_vm_writev(getpid(), &local, 1, &remote, 1, 0) != static_cast<ssize_t>(entry.width))
            return EFAULT;
        ++state.resetWritten;
    }
    return 0;
}
} // namespace

bool validCaptureLayout(const FmLinuxCaptureLayout &layout, const FmLinuxUiLayout &ui) {
    if (!validUiLayout(ui) || layout.guiSize != ui.guiSize || layout.widgetSize != ui.widgetSize ||
        !validTargeterLayout(layout.targeter) || !fm::addressRange(layout.targeter.release, 1) ||
        !fm::member(layout.widgetTargetable, layout.targeter.targetableExtent, ui.widgetSize) ||
        !fm::member(layout.targeterMember, layout.targeter.targeterExtent, ui.guiSize) ||
        !layout.resetCount || layout.resetCount > FM_LINUX_CAPTURE_RESETS)
        return false;
    for (uint32_t index = 0; index < layout.resetCount; ++index) {
        const auto &entry = layout.resets[index];
        if ((entry.width != 1 && entry.width != 2 && entry.width != 4 && entry.width != 8) ||
            !fm::member(entry.offset, entry.width, ui.guiSize) ||
            (entry.offset < layout.targeterMember + layout.targeter.targeterExtent &&
             layout.targeterMember < entry.offset + entry.width) ||
            (entry.offset < ui.root + sizeof(uintptr_t) && ui.root < entry.offset + entry.width))
            return false;
        for (uint32_t previous = 0; previous < index; ++previous) {
            const auto &other = layout.resets[previous];
            if (entry.offset < other.offset + other.width && other.offset < entry.offset + entry.width)
                return false;
        }
    }
    return true;
}

int beginUiCapture(uintptr_t guiInstance, const FmLinuxUiLayout &ui, const FmLinuxCaptureLayout &layout,
                   const UiTarget &target, UiCapture &output) {
    output = {};
    if (!validCaptureLayout(layout, ui))
        return EINVAL;
    if (const int error = liveUiTarget(guiInstance, ui, target))
        return error;
    TargeterLinks links;
    if (const int error = readTargeter(target.gui + layout.targeterMember, layout.targeter, links))
        return error;
    output.gui = target.gui;
    output.root = target.root;
    output.initial = links.target;
    return 0;
}

int finishUiCapture(uintptr_t guiInstance, const FmLinuxUiLayout &ui, const FmLinuxCaptureLayout &layout,
                    const FmLinuxMouseEventLayout &event, const FmLinuxInputClockLayout &clock,
                    void (*up)(void *, const void *), const UiMouseRequest &request,
                    int64_t absoluteX, int64_t absoluteY, uintptr_t alreadyReleased, UiCapture &state) {
    if (!validCaptureLayout(layout, ui) || !state.gui || !state.root || !up ||
        !validMouseEventLayout(event) || !validInputClockLayout(clock) || clock.guiSize != ui.guiSize ||
        !request.button || state.resetWritten > layout.resetCount ||
        absoluteX < INT32_MIN || absoluteX > int64_t{INT32_MAX} * 2 ||
        absoluteY < INT32_MIN || absoluteY > int64_t{INT32_MAX} * 2)
        return EINVAL;
    const auto result = [&](int error) {
        if (error && !state.failure)
            state.failure = error;
        return state.failure;
    };
    if (state.finished)
        return state.failure;
    uintptr_t initial = state.initial;
    if (state.initialReference) {
        if (const int error = state.initialReference->borrow(initial))
            return result(error);
    }
    int error = current(guiInstance, ui, state);
    if (error) {
        state.finished = error == ESTALE;
        return result(error == ESTALE ? 0 : error);
    }
    const uintptr_t address = state.gui + layout.targeterMember;
    TargeterLinks links;
    if ((error = readTargeter(address, layout.targeter, links)))
        return result(error);
    if (links.target == initial && links.target) {
        state.finished = true;
        return state.failure;
    }
    if (!links.target && !state.cleanupStarted) {
        state.finished = true;
        return state.failure;
    }
    state.cleanupStarted = true;
    int dispatchError = 0;
    if (links.target && !state.upAttempted) {
        // Only entering a callback makes its effect uncertain. Pre-entry failures retain the pending release.
        if (links.target < layout.widgetTargetable)
            dispatchError = EFAULT;
        else {
            UiTarget target{state.gui, state.root, links.target - layout.widgetTargetable, {}};
            if (target.widget != alreadyReleased) {
                if (state.recipientReference) {
                    const auto expected = target.widget;
                    if (!state.recipientReference->owned())
                        dispatchError = state.recipientReference->attach(guiInstance, ui, layout, target);
                    if (!dispatchError) {
                        dispatchError = state.recipientReference->borrow(guiInstance, ui, target);
                        if (!dispatchError && target.widget != expected)
                            dispatchError = ESTALE;
                    }
                }
                if (!dispatchError)
                    dispatchError = refreshUiTarget(guiInstance, ui, target);
                if (!dispatchError) {
                    const int64_t x = absoluteX - target.bounds.x;
                    const int64_t y = absoluteY - target.bounds.y;
                    if (x < INT32_MIN || x > INT32_MAX || y < INT32_MIN || y > INT32_MAX)
                        dispatchError = ERANGE;
                    else {
                        dispatchError = dispatchUiMouseAt(guiInstance, ui, event, clock, up, target, request,
                            static_cast<int32_t>(x), static_cast<int32_t>(y), state.up);
                        if (state.up.entered) {
                            state.upAttempted = true;
                            state.released = target.widget;
                        } else if (state.up.targetStatus == ENOENT || state.up.targetStatus == ESTALE) {
                            state.upAttempted = true;
                            dispatchError = 0;
                        }
                    }
                } else if (dispatchError == ENOENT || dispatchError == ESTALE) {
                    state.upAttempted = true;
                    dispatchError = 0;
                }
            } else
                state.upAttempted = true;
        }
        if (!state.upAttempted)
            return result(dispatchError ? dispatchError : EPROTO);
    }
    result(dispatchError);
    error = current(guiInstance, ui, state);
    if (error) {
        state.finished = error == ESTALE;
        return result(error == ESTALE ? 0 : error);
    }
    // The up handler may transfer capture to another widget or restore the preexisting capture.
    error = readTargeter(address, layout.targeter, links);
    if (!error && state.initialReference)
        error = state.initialReference->borrow(initial);
    if (!error && links.target && links.target == initial) {
        state.finished = true;
        return state.failure;
    }
    if (!error)
        error = releaseTargeter(address, layout.targeter);
    if (!error)
        error = reset(state.gui, layout, state);
    state.finished = !error;
    return result(error);
}
