#include "ui_snapshot.h"
#include "mouse_event.h"
#include "ui_capture.h"
#include "ui_modal.h"
#include "ui_gesture.h"
#include "ui_reference.h"
#include "ui_command.h"
#include "ui_click.h"
#include "ui_text.h"
#include "ui_key.h"
#include <cxxabi.h>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <limits>
#include <string>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>

static void require(bool value, const char *message) {
    if (!value) {
        std::fprintf(stderr, "factorio-mcp UI fixture: %s\n", message);
        std::abort();
    }
}

struct Text {
    uint64_t prefix = 0;
    const char *data = "";
    size_t length = 0;
};

struct CaptureLink;
struct CaptureTarget { CaptureLink *head = nullptr; };
struct CaptureLink {
    CaptureTarget *target = nullptr;
    CaptureLink *previous = nullptr;
    CaptureLink *next = nullptr;
};

static unsigned unlinkMode = 0;
static unsigned unlinkCalls = 0;
static void *resetPage = nullptr;
static size_t resetPageSize = 0;

static void unlinkCapture(void *pointer, void *argument) {
    require(!argument, "capture release did not receive null");
    ++unlinkCalls;
    if (unlinkMode == 1)
        throw 1;
    auto &link = *static_cast<CaptureLink *>(pointer);
    if (link.target) {
        if (link.previous)
            link.previous->next = link.next;
        else
            link.target->head = link.next;
        if (link.next)
            link.next->previous = link.previous;
        link = {};
    }
    if (unlinkMode == 2)
        require(mprotect(resetPage, resetPageSize, PROT_READ) == 0, "cannot protect reset fixture page");
}

static void capture(CaptureLink &link, CaptureTarget &target) {
    if (link.target)
        unlinkCapture(&link, nullptr);
    link.target = &target;
    link.next = target.head;
    if (target.head)
        target.head->previous = &link;
    target.head = &link;
}

static void assignCapture(void *pointer, void *target) {
    if (target)
        capture(*static_cast<CaptureLink *>(pointer), *static_cast<CaptureTarget *>(target));
    else
        unlinkCapture(pointer, nullptr);
}

static void clearReferences(CaptureTarget &target) {
    while (target.head)
        unlinkCapture(target.head, nullptr);
}

struct Option;
struct Widget {
    virtual ~Widget() = default;
    virtual const Text &getText() const {
        return text;
    }
    virtual bool isToggled() const { return toggled; }

    uint64_t padding[3]{};
    Widget **first = nullptr;
    Widget **last = nullptr;
    Widget **privateFirst = nullptr;
    Widget **privateLast = nullptr;
    uint8_t flags = 8;
    uint8_t toggled = 0;
    uint8_t toggleMode = 1;
    int32_t checkState = 0;
    double value = 0;
    double minimum = 0;
    double maximum = 0;
    double step = 0;
    uint8_t progressDirection = 0;
    uint8_t progressHasText = 0;
    int32_t selectedIndex = -1;
    Option *optionFirst = nullptr;
    Option *optionLast = nullptr;
    Text text;
    Widget *parent = nullptr;
    FmLinuxRectangle bounds{};
    CaptureTarget captureTarget;
};

struct Option {
    uint64_t prefix = 0;
    Widget *button = nullptr;
    uint64_t suffix = 0;
};

static unsigned rectangleCalls = 0;

static FmLinuxRectangle rectangle(const void *pointer) {
    ++rectangleCalls;
    const auto *widget = static_cast<const Widget *>(pointer);
    auto result = widget->bounds;
    for (auto parent = widget->parent; parent; parent = parent->parent) {
        result.x += parent->bounds.x;
        result.y += parent->bounds.y;
    }
    return result;
}

struct Label : Widget {
    Text label;
    const Text &getText() const override {
        return label;
    }
};

struct Unknown : Widget {
    const Text &getText() const override {
        return text;
    }
};
struct InputClock { const uintptr_t *table; double time; };
struct ModalEntry { uint64_t prefix = 0; CaptureTarget *target = nullptr; uint64_t suffix[3]{}; };
struct Gui {
    uint64_t prefix[7]{};
    Widget *root;
    InputClock *input = nullptr;
    CaptureLink capture;
    uint8_t dragging = 0;
    uint64_t origin = 0;
    ModalEntry *modalBegin = nullptr;
    ModalEntry *modalEnd = nullptr;
    CaptureTarget *previous = nullptr;
};

struct MouseEvent {
    uint64_t unused = 0;
    Widget *source = nullptr;
    double time = 0;
    int32_t x = 0;
    int32_t y = 0;
    uint32_t type = 0;
    int32_t wheel = 0;
    uint16_t button = 0;
    uint8_t alt = 0;
    uint8_t control = 0;
    uint8_t shift = 0;
    uint64_t extra = 0;
};

static Gui *activeGui;
static Widget *replacement;
static unsigned dispatchCalls = 0;
static unsigned dispatchMode = 0;
static MouseEvent received;
static unsigned clockMode = 0;
static unsigned clockCalls = 0;
static unsigned captureUpMode = 0;
static unsigned captureUpCalls = 0;
static Widget *captureRecipient = nullptr;
static uintptr_t expectedMousePrevious = 0;

static double inputTime(const void *clock) {
    ++clockCalls;
    if (clockMode == 1)
        activeGui->root = replacement;
    if (clockMode == 2)
        throw 1;
    if (clockMode == 3) {
        clockMode = 0;
        throw 1;
    }
    if (clockMode == 4)
        replacement->flags |= 1;
    if (clockMode == 5)
        clearReferences(replacement->captureTarget);
    return static_cast<const InputClock *>(clock)->time;
}

static void mouseEvent(void *widget, const void *event) {
    ++dispatchCalls;
    std::memcpy(&received, event, sizeof(received));
    require(widget == received.source && !received.unused && received.extra == expectedMousePrevious && !received.wheel,
            "mouse event source/defaults were lost");
    if (dispatchMode == 1 || dispatchMode == 4)
        activeGui->root->first[0] = replacement;
    if (dispatchMode == 2)
        activeGui->root = replacement;
    if (dispatchMode == 3 || dispatchMode == 4)
        throw 1;
    if (dispatchMode == 5)
        activeGui->root->first[0] = activeGui->root;
    if (dispatchMode == 6)
        clearReferences(static_cast<Widget *>(widget)->captureTarget);
}

static void captureUp(void *widget, const void *event) {
    ++captureUpCalls;
    mouseEvent(widget, event);
    if (captureUpMode == 1 || captureUpMode == 2)
        capture(activeGui->capture, captureRecipient->captureTarget);
    if (captureUpMode == 2)
        throw 1;
    if (captureUpMode == 3)
        activeGui->root = replacement;
    if (captureUpMode == 4)
        static_cast<Widget *>(widget)->flags |= 1;
}

static std::vector<unsigned> gestureCalls;
static unsigned gestureFailure = 0;
static unsigned gestureMode = 0;
static Widget *gesturePrevious = nullptr;
static std::vector<Widget *> gestureRecipients;

static void gestureEvent(unsigned step, void *pointer, const void *event) {
    auto *widget = static_cast<Widget *>(pointer);
    gestureCalls.push_back(step);
    gestureRecipients.push_back(widget);
    expectedMousePrevious = step == 1 ? reinterpret_cast<uintptr_t>(gesturePrevious) : 0;
    mouseEvent(pointer, event);
    expectedMousePrevious = 0;
    require(received.type == step + 100 && received.button == 64 && received.alt && received.shift,
            "gesture lost its resolved event type or requested chord");
    if (step == 2) {
        capture(activeGui->capture, (gestureMode == 2 || gestureMode == 4 ? captureRecipient : widget)->captureTarget);
        activeGui->dragging = 1;
        activeGui->origin = 123;
        if (gestureMode == 1) {
            widget->flags |= 16;
            auto click = received;
            click.type = 103;
            gestureEvent(3, widget, &click);
        }
        if (gestureMode == 2)
            activeGui->root->first[0] = replacement;
        if (gestureMode == 3)
            activeGui->root = replacement;
    }
    if (step == 3 && gestureMode == 5)
        clockMode = 3;
    if (step == 3 && gestureMode == 6)
        unlinkMode = 1;
    if (step == 3 && gestureMode == 7)
        activeGui->input = nullptr;
    if (step == 5 && gestureMode == 8)
        unlinkMode = 1;
    if (step == 5 && (gestureMode == 9 || gestureMode == 10)) {
        capture(activeGui->capture, captureRecipient->captureTarget);
        activeGui->dragging = 1;
        activeGui->origin = 456;
        if (gestureMode == 10)
            activeGui->input = nullptr;
    }
    if (step == gestureFailure)
        throw 1;
}

static void gestureEnter(void *widget, const void *event) {
    gestureEvent(1, widget, event);
}

static void gestureDown(void *widget, const void *event) {
    gestureEvent(2, widget, event);
}

static void gestureClick(void *widget, const void *event) {
    gestureEvent(3, widget, event);
}

static void gestureUp(void *widget, const void *event) {
    gestureEvent(4, widget, event);
}

static void gestureLeave(void *widget, const void *event) {
    gestureEvent(5, widget, event);
}

static void checkGestures(const UiGestureContext &context, const UiTarget &target, const UiMouseRequest &request,
                          Widget &root, Widget &child, Widget &other) {
    const auto childFlags = child.flags;
    const auto otherFlags = other.flags;
    const auto otherBounds = other.bounds;
    auto *const originalClock = activeGui->input;
    const auto reset = [&] {
        gestureCalls.clear();
        gestureRecipients.clear();
        gestureFailure = 0;
        gestureMode = 0;
        gesturePrevious = nullptr;
        activeGui->previous = nullptr;
        unlinkMode = 0;
        clockMode = 0;
        activeGui->input = originalClock;
        activeGui->root = &root;
        root.first[0] = &child;
        child.flags = 8;
        other.flags = 8;
        other.bounds = {20, 30, 15, 12};
        captureRecipient = &other;
        replacement = &other;
        unlinkCapture(&activeGui->capture, nullptr);
    };
    reset();
    require(validMouseGesture(context), "valid gesture metadata rejected");
    gesturePrevious = &other;
    activeGui->previous = &other.captureTarget;
    UiMouseGesture withPrevious;
    require(runUiMouseGesture(context, target, request, withPrevious) == 0 && withPrevious.finished &&
            !withPrevious.request.previous, "native previous widget was lost or reused after enter");
    reset();
    activeGui->previous = reinterpret_cast<CaptureTarget *>(1);
    UiMouseGesture invalidPrevious;
    require(runUiMouseGesture(context, target, request, invalidPrevious) == EFAULT &&
            !invalidPrevious.started && gestureCalls.empty(), "invalid previous target reached enter");
    reset();
    gesturePrevious = &other;
    activeGui->previous = &other.captureTarget;
    other.flags |= 1;
    UiMouseGesture destroyedPrevious;
    require(runUiMouseGesture(context, target, request, destroyedPrevious) == ENOENT &&
            !destroyedPrevious.started && gestureCalls.empty(), "destroying previous target reached enter");
    reset();
    gesturePrevious = &other;
    activeGui->previous = &other.captureTarget;
    clockMode = 4;
    UiMouseGesture changedPrevious;
    require(runUiMouseGesture(context, target, request, changedPrevious) == ENOENT && changedPrevious.finished &&
            !changedPrevious.enter.entered && gestureCalls.empty(), "previous target was not rechecked after the clock callback");
    for (unsigned failure = 0; failure <= 5; ++failure) {
        reset();
        gestureFailure = failure;
        UiMouseGesture state;
        require(runUiMouseGesture(context, target, request, state) == (failure ? EIO : 0) &&
                state.started && state.finished && !state.upPending && !state.leavePending &&
                !activeGui->capture.target, "finite gesture lost callback failure or left cleanup pending");
        const std::vector<unsigned> expected = failure == 1 ? std::vector<unsigned>{1, 5} :
            failure == 2 ? std::vector<unsigned>{1, 2, 4, 5} : std::vector<unsigned>{1, 2, 3, 4, 5};
        require(gestureCalls == expected, "gesture skipped cleanup or replayed a callback after an exception");
        require(finishUiMouseGesture(context, state) == (failure ? EIO : 0) && gestureCalls == expected &&
                runUiMouseGesture(context, target, request, state) == EALREADY,
                "completed gesture forgot failure or replayed admitted work");
    }
    reset();
    gestureMode = 1;
    UiMouseGesture nested;
    require(runUiMouseGesture(context, target, request, nested) == 0 && nested.finished && nested.clickSkipped &&
            !nested.click.entered && gestureCalls == std::vector<unsigned>({1, 2, 3, 4, 5}),
            "gesture repeated the click already dispatched by down with a changed flag");
    for (unsigned mode : {2U, 3U, 4U}) {
        reset();
        gestureMode = mode;
        UiMouseGesture changed;
        require(runUiMouseGesture(context, target, request, changed) == 0 && changed.finished,
                "gesture did not finish after a lifetime/capture transition");
        const std::vector<unsigned> expected = mode == 2 ? std::vector<unsigned>{1, 2, 4} :
            mode == 3 ? std::vector<unsigned>{1, 2} : std::vector<unsigned>{1, 2, 3, 4, 4, 5};
        require(gestureCalls == expected, "gesture used a stale target or failed to release transferred capture");
        if (mode == 2 || mode == 4)
            require(gestureRecipients[mode == 2 ? 2 : 4] == &other && !activeGui->capture.target,
                    "capture up was delivered to the wrong widget");
        if (mode == 3)
            require(activeGui->capture.target == &child.captureTarget && activeGui->dragging == 1,
                    "gesture wrote to the old GUI state after root replacement");
    }
    reset();
    gestureMode = 5;
    UiMouseGesture clockFailure;
    require(runUiMouseGesture(context, target, request, clockFailure) == EIO && clockFailure.finished &&
            !clockFailure.up.entered && clockFailure.capture.up.entered &&
            clockFailure.capture.released == target.widget &&
            gestureCalls == std::vector<unsigned>({1, 2, 3, 4, 5}),
            "capture cleanup did not discharge the original up after a pre-entry clock failure");
    const auto clockCallsBefore = gestureCalls;
    require(finishUiMouseGesture(context, clockFailure) == EIO && gestureCalls == clockCallsBefore,
            "cleanup replayed an up delivered through capture");
    reset();
    gestureMode = 6;
    gestureFailure = 3;
    UiMouseGesture retry;
    require(runUiMouseGesture(context, target, request, retry) == EIO && !retry.finished &&
            !retry.upPending && retry.leavePending && activeGui->capture.target,
            "failed capture cleanup discarded ownership or the earlier click failure");
    auto beforeRetry = gestureCalls;
    beforeRetry.push_back(5);
    unlinkMode = 0;
    require(finishUiMouseGesture(context, retry) == EIO && retry.finished && !activeGui->capture.target &&
            gestureCalls == beforeRetry, "capture retry replayed gesture callbacks or lost the first failure");
    reset();
    auto invalid = request;
    invalid.x = std::numeric_limits<double>::quiet_NaN();
    UiMouseGesture rejected;
    require(runUiMouseGesture(context, target, invalid, rejected) == EINVAL && !rejected.started && gestureCalls.empty(),
            "invalid gesture admitted callbacks");
    activeGui->input = nullptr;
    UiMouseGesture missingClock;
    require(runUiMouseGesture(context, target, request, missingClock) == ENOENT && missingClock.finished &&
            !missingClock.enter.entered && gestureCalls.empty(),
            "missing input clock was mistaken for normal target removal");
    reset();
    gestureMode = 7;
    UiMouseGesture pendingClock;
    require(runUiMouseGesture(context, target, request, pendingClock) == ENOENT && !pendingClock.finished &&
            pendingClock.upPending && pendingClock.leavePending && activeGui->capture.target &&
            gestureCalls == std::vector<unsigned>({1, 2, 3}),
            "pre-entry cleanup failures discarded the pending original up/leave");
    activeGui->input = originalClock;
    require(finishUiMouseGesture(context, pendingClock) == ENOENT && pendingClock.finished &&
            gestureCalls == std::vector<unsigned>({1, 2, 3, 4, 5}),
            "cleanup recovery repeated the click or lost its original missing-clock failure");
    auto ownedCapture = context.capture;
    ownedCapture.targeter.release = reinterpret_cast<uintptr_t>(&assignCapture);
    UiGestureContext ownedContext{context.guiInstance, context.ui, context.event, context.clock, ownedCapture,
        context.gesture, context.functions};
    for (unsigned failure = 0; failure <= 5; ++failure) {
        reset();
        gestureFailure = failure;
        UiMouseCommand command;
        require(command.start(ownedContext, target, request) == (failure ? EIO : 0) && command.finished(),
                "owned command failed to finish its finite callbacks and references");
        const auto beforeFinish = gestureCalls;
        require(command.finish(ownedContext) == (failure ? EIO : 0) && gestureCalls == beforeFinish &&
                command.start(ownedContext, target, request) == EALREADY,
                "owned command replayed a completed gesture or lost its first failure");
        require(!root.captureTarget.head && !child.captureTarget.head && !other.captureTarget.head,
                "completed command left registered native references");
    }
    reset();
    gestureMode = 7;
    UiMouseCommand deferred;
    require(deferred.start(ownedContext, target, request) == ENOENT && !deferred.finished() &&
            deferred.progress().upPending && deferred.progress().leavePending,
            "owned command dropped deferred up/leave progress");
    activeGui->input = originalClock;
    require(deferred.finish(ownedContext) == ENOENT && deferred.finished() &&
            gestureCalls == std::vector<unsigned>({1, 2, 3, 4, 5}),
            "owned command could not reconstruct and finish its deferred cleanup");
    reset();
    gestureMode = 6;
    gestureFailure = 3;
    UiMouseCommand blockedUnlink;
    require(blockedUnlink.start(ownedContext, target, request) == EIO && !blockedUnlink.finished(),
            "owned command lost its failed capture cleanup");
    auto beforeUnlinkRetry = gestureCalls;
    beforeUnlinkRetry.push_back(5);
    require(blockedUnlink.finish(ownedContext) == EIO && !blockedUnlink.finished(),
            "repeated unlink failure discarded native ownership");
    unlinkMode = 0;
    require(blockedUnlink.finish(ownedContext) == EIO && blockedUnlink.finished() &&
            gestureCalls == beforeUnlinkRetry && !activeGui->capture.target,
            "owned capture cleanup retry replayed callbacks or lost the original click failure");
    reset();
    gestureMode = 8;
    UiMouseCommand blockedReferences;
    require(blockedReferences.start(ownedContext, target, request) == EFAULT && !blockedReferences.finished() &&
            blockedReferences.progress().finished, "reference-release failure reported a completed command");
    const auto beforeReferenceRetry = gestureCalls;
    unlinkMode = 0;
    require(blockedReferences.finish(ownedContext) == EFAULT && blockedReferences.finished() &&
            gestureCalls == beforeReferenceRetry, "reference-release retry replayed the gesture");
    for (unsigned mode : {9U, 10U}) {
        reset();
        gestureMode = mode;
        UiMouseCommand leaveCapture;
        require(leaveCapture.start(ownedContext, target, request) == (mode == 9 ? 0 : ENOENT),
                "leave-capture cleanup lost its first failure");
        if (mode == 10) {
            require(!leaveCapture.finished() && activeGui->capture.target == &other.captureTarget &&
                    gestureCalls == std::vector<unsigned>({1, 2, 3, 4, 5}),
                    "pre-entry failure dropped the capture acquired by leave");
            activeGui->input = originalClock;
            require(leaveCapture.finish(ownedContext) == ENOENT, "leave-capture retry lost its original failure");
        }
        require(leaveCapture.finished() && !activeGui->capture.target && !activeGui->dragging &&
                activeGui->origin == 0x7fffffff7fffffffULL &&
                gestureCalls == std::vector<unsigned>({1, 2, 3, 4, 5, 4}) && gestureRecipients.back() == &other,
                "gesture left capture acquired by leave active or replayed a prior phase");
    }
    reset();
    gestureMode = 7;
    UiMouseCommand changedCapture;
    require(changedCapture.start(ownedContext, target, request) == ENOENT && !changedCapture.finished(),
            "foreign-capture fixture did not retain cleanup");
    activeGui->input = originalClock;
    capture(activeGui->capture, other.captureTarget);
    const auto beforeForeign = gestureCalls;
    require(changedCapture.finish(ownedContext) == ENOENT && !changedCapture.finished() &&
            activeGui->capture.target == &other.captureTarget && gestureCalls == beforeForeign,
            "deferred cleanup modified capture acquired by unrelated input");
    capture(activeGui->capture, child.captureTarget);
    require(changedCapture.finish(ownedContext) == ENOENT && changedCapture.finished(),
            "restored capture ownership could not finish cleanup");
    reset();
    gestureMode = 7;
    UiMouseCommand lostRoot;
    require(lostRoot.start(ownedContext, target, request) == ENOENT && !lostRoot.finished(),
            "root-invalidation fixture did not retain cleanup");
    const auto beforeRootLoss = gestureCalls;
    clearReferences(root.captureTarget);
    require(lostRoot.finish(ownedContext) == ENOENT && lostRoot.finished() && gestureCalls == beforeRootLoss,
            "deferred command reused a destroyed root or failed to release references");
    reset();
    gesturePrevious = &other;
    activeGui->previous = &other.captureTarget;
    clockMode = 5;
    UiMouseCommand lostPrevious;
    require(lostPrevious.start(ownedContext, target, request) == ENOENT && lostPrevious.finished() && gestureCalls.empty(),
            "enter used a previous widget whose native reference was invalidated during its clock callback");
    reset();
    child.flags = childFlags;
    other.flags = otherFlags;
    other.bounds = otherBounds;
}

struct ClickKey { int32_t code = 0; uintptr_t padding = 0; uint8_t held = 0; uint8_t blocked = 0; };
struct ClickKeyMap { uintptr_t padding = 0; ClickKey *begin = nullptr; ClickKey *end = nullptr; };
struct ClickInputState { uintptr_t padding = 0; uint32_t held = 0; ClickKeyMap keys; };
struct ClickGlobal { uintptr_t padding = 0; ClickInputState *state = nullptr; };
struct ClickInputEvent { uint32_t type; double time; uint32_t code; };
static ClickGlobal *clickGlobal = nullptr;
static std::vector<unsigned> clickEdges;
static std::vector<unsigned> clickSteps;
static std::vector<unsigned> clickKeyEdges;
static unsigned clickModifiers = 0;
static unsigned clickFailure = 0;
static unsigned clickMode = 0;
static uint16_t clickButton = 0;
static uint32_t clickMask = 0;
static uint32_t *clickCancel = nullptr;

static void clickUpdate(void *receiver, const void *event) {
    const auto &value = *static_cast<const ClickInputEvent *>(event);
    auto &state = *static_cast<ClickInputState *>(receiver);
    require(activeGui->input && value.time == activeGui->input->time, "click did not use the verified native clock");
    if (value.type == 211 || value.type == 212) {
        ClickKey *key = nullptr;
        for (auto *cursor = state.keys.begin; cursor != state.keys.end; ++cursor)
            if (cursor->code == int32_t(value.code))
                key = cursor;
        require(key, "click used an unknown native modifier code");
        clickKeyEdges.push_back(value.code + (value.type == 212 ? 256 : 0));
        if (value.type == 211)
            key->held = 1;
        else {
            require(!(state.held & clickMask), "modifier was released before its mouse button");
            if (!(clickMode == 7 && value.code == 29))
                key->held = 0;
        }
        if ((clickMode == 5 && value.type == 211 || clickMode == 6 && value.type == 212) && value.code == 29)
            throw 1;
        if (clickMode == 8 && value.type == 211)
            *clickCancel = 1;
        return;
    }
    const uint32_t mask = value.code == 71 ? 4 : value.code == 82 ? 32 : value.code == 93 ? 128 : 0;
    require(mask != 0, "click mixed GUI button values with input codes");
    clickEdges.push_back(value.type);
    if (value.type == 201)
        state.held |= mask;
    else if (value.type == 202)
        state.held &= ~mask;
    else
        require(false, "click input edge kind was not resolved");
    if (clickMode == 1 && value.type == 201)
        throw 1;
    if (clickMode == 2 && value.type == 202)
        throw 1;
    if (clickMode == 3 && value.type == 201)
        *clickCancel = 1;
}

static void clickPost(void *, const void *) {}

static void clickStep(unsigned step, void *widget, const void *event) {
    const auto &value = *static_cast<const MouseEvent *>(event);
    clickSteps.push_back(step);
    require(value.type == step + 100 && value.button == clickButton && bool(value.control) == bool(clickModifiers & 1) &&
            bool(value.shift) == bool(clickModifiers & 2) && bool(value.alt) == bool(clickModifiers & 4),
            "click lost its event fields or modifier flags");
    require(clickGlobal->state->held & clickMask, "finite click callbacks did not see the held button");
    unsigned index = 0;
    for (auto *key = clickGlobal->state->keys.begin; key != clickGlobal->state->keys.end; ++key, ++index)
        require(bool(key->held) == bool(clickModifiers & (1U << index)), "GUI callback did not see mirrored modifier state");
    if (step == 2)
        capture(activeGui->capture, static_cast<Widget *>(widget)->captureTarget);
    if (step == 3 && clickMode == 4)
        activeGui->input = nullptr;
    if (step == clickFailure)
        throw 1;
}

static void clickEnter(void *widget, const void *event) {
    clickStep(1, widget, event);
}

static void clickDown(void *widget, const void *event) {
    clickStep(2, widget, event);
}

static void clickDispatch(void *widget, const void *event) {
    clickStep(3, widget, event);
}

static void clickUp(void *widget, const void *event) {
    clickStep(4, widget, event);
}

static void clickLeave(void *widget, const void *event) {
    clickStep(5, widget, event);
}


static void checkClicks(const UiGestureContext &context, const FmLinuxModalLayout &modal,
                         const FmLinuxUiSelector &selector, FmLinuxUiSnapshot &scratch,
                         Widget &root, Widget &child, Widget &other) {
    ClickInputState state;
    ClickKey keys[3];
    ClickGlobal global{0, &state};
    clickGlobal = &global;
    uint32_t cancel = 0;
    clickCancel = &cancel;
    FmLinuxUiClickConfig config{};
    auto &g = config.gesture;
    g.event = context.event;
    g.clock = context.clock;
    g.capture = context.capture;
    g.capture.targeter.release = reinterpret_cast<uintptr_t>(&assignCapture);
    g.modal = modal;
    g.gesture = context.gesture;
    g.enter = reinterpret_cast<uintptr_t>(&clickEnter);
    g.down = reinterpret_cast<uintptr_t>(&clickDown);
    g.click = reinterpret_cast<uintptr_t>(&clickDispatch);
    g.up = reinterpret_cast<uintptr_t>(&clickUp);
    g.leave = reinterpret_cast<uintptr_t>(&clickLeave);
    g.buttons[0] = 64;
    g.buttons[1] = 256;
    g.buttons[2] = 512;
    config.state.layout = {reinterpret_cast<uintptr_t>(&clickGlobal), sizeof(ClickGlobal), offsetof(ClickGlobal, state),
        sizeof(ClickInputState), offsetof(ClickInputState, held), sizeof(ClickInputEvent), offsetof(ClickInputEvent, type),
        offsetof(ClickInputEvent, time), offsetof(ClickInputEvent, code), 201, 202, {71, 82, 93}, {4, 32, 128}};
    config.state.update = reinterpret_cast<uintptr_t>(&clickUpdate);
    config.state.postUpdate = reinterpret_cast<uintptr_t>(&clickPost);
    config.codes[0] = 71;
    config.codes[1] = 93;
    config.codes[2] = 82;
    config.keyboard = {{offsetof(ClickInputState, keys), offsetof(ClickKeyMap, begin), offsetof(ClickKeyMap, end),
        sizeof(ClickKey), offsetof(ClickKey, code), offsetof(ClickKey, held), 2, 0, 1}, offsetof(ClickInputEvent, code),
        211, 212, {17, 29, 43}};
    auto *const clock = activeGui->input;
    const auto rootFlags = root.flags;
    const auto childFlags = child.flags;
    const auto reset = [&] {
        clickEdges.clear();
        clickSteps.clear();
        clickKeyEdges.clear();
        clickModifiers = 0;
        clickFailure = clickMode = cancel = 0;
        unlinkMode = clockMode = 0;
        activeGui->input = clock;
        activeGui->previous = nullptr;
        activeGui->modalBegin = activeGui->modalEnd = nullptr;
        root.flags = child.flags = 8 | 2;
        state.held = 1024;
        for (unsigned index = 0; index < 3; ++index)
            keys[index] = {int32_t(config.keyboard.codes[index]), 0, 0, 0};
        state.keys = {0, keys, keys + 3};
        clickButton = 64;
        clickMask = 4;
    };
    FmLinuxUiClickRequest request{0, 0.5, 0.5};
    for (unsigned button = 0; button < 3; ++button) {
        for (unsigned failure = 0; failure <= 5; ++failure) {
            reset();
            request.button = button;
            clickButton = g.buttons[button];
            clickMask = button == 0 ? 4 : button == 1 ? 128 : 32;
            clickFailure = failure;
            UiClickCommand command;
            require(command.start(context.guiInstance, activeGui, context.ui, config, selector, request, &cancel, scratch)
                    == (failure ? EIO : 0) && command.finished() && state.held == 1024 &&
                    clickEdges == std::vector<unsigned>({201, 202}), "click did not pair its input state on every GUI failure");
            const auto steps = clickSteps;
            require(command.finish() == (failure ? EIO : 0) && clickSteps == steps && clickEdges.size() == 2 &&
                    command.start(context.guiInstance, activeGui, context.ui, config, selector, request, &cancel, scratch)
                    == EALREADY, "finished click replayed input or GUI events");
            require(!activeGui->capture.target && !root.captureTarget.head && !child.captureTarget.head &&
                    !other.captureTarget.head, "finished click retained GUI capture or native references");
        }
    }
    request.button = 0;
    for (unsigned modifiers = 1; modifiers < 8; ++modifiers) {
        for (unsigned failure = 0; failure <= 5; ++failure) {
            reset();
            clickModifiers = modifiers;
            clickFailure = failure;
            request.control = modifiers & 1;
            request.shift = (modifiers >> 1) & 1;
            request.alt = (modifiers >> 2) & 1;
            std::vector<unsigned> edges;
            for (unsigned index = 0; index < 3; ++index)
                if (modifiers & (1U << index))
                    edges.push_back(config.keyboard.codes[index]);
            for (unsigned index = 3; index-- > 0;)
                if (modifiers & (1U << index))
                    edges.push_back(256 + config.keyboard.codes[index]);
            UiClickCommand command;
            require(command.start(context.guiInstance, activeGui, context.ui, config, selector, request, &cancel, scratch)
                    == (failure ? EIO : 0) && command.finished() && clickKeyEdges == edges &&
                    !keys[0].held && !keys[1].held && !keys[2].held && state.held == 1024,
                    "modified click failed to pair its chord across a GUI failure");
        }
    }
    request.control = request.shift = request.alt = 1;
    for (unsigned mode : {5U, 6U, 8U}) {
        reset();
        clickModifiers = 7;
        clickMode = mode;
        UiClickCommand command;
        require(command.start(context.guiInstance, activeGui, context.ui, config, selector, request, &cancel, scratch)
                == (mode == 8 ? 0 : EIO) && command.finished() &&
                !keys[0].held && !keys[1].held && !keys[2].held && state.held == 1024,
                "modified click lost cleanup on key failure or admitted cancellation");
        require(mode != 5 || (clickSteps.empty() && clickEdges.empty() &&
                clickKeyEdges == std::vector<unsigned>({17, 29, 285, 273})),
                "failed modifier admission continued its gesture or discarded earlier ownership");
    }
    reset();
    clickModifiers = 7;
    clickMode = 7;
    UiClickCommand uncertain;
    require(uncertain.start(context.guiInstance, activeGui, context.ui, config, selector, request, &cancel, scratch)
            == EPROTO && !uncertain.finished() && keys[1].held && !keys[0].held && !keys[2].held,
            "uncertain modifier release dropped ownership or skipped independent cleanup");
    const auto enteredEdges = clickKeyEdges;
    clickMode = 0;
    require(uncertain.finish() == EPROTO && !uncertain.finished() && clickKeyEdges == enteredEdges,
            "uncertain key release was replayed");
    keys[1].held = 0;
    require(uncertain.finish() == EPROTO && uncertain.finished() && clickKeyEdges == enteredEdges,
            "cleared key state could not discharge retained ownership");
    reset();
    keys[2].blocked = 1;
    UiClickCommand blocked;
    require(blocked.start(context.guiInstance, activeGui, context.ui, config, selector, request, &cancel, scratch)
            == EBUSY && blocked.finished() && clickSteps.empty() && clickEdges.empty() && keys[2].blocked == 1 &&
            !keys[0].held && !keys[1].held && clickKeyEdges == std::vector<unsigned>({17, 29, 285, 273}),
            "blocked modifier admission changed foreign state or lost earlier chord cleanup");
    request.control = request.shift = request.alt = 0;
    for (unsigned mode : {1U, 2U, 3U}) {
        reset();
        clickMode = mode;
        UiClickCommand command;
        require(command.start(context.guiInstance, activeGui, context.ui, config, selector, request, &cancel, scratch)
                == (mode == 3 ? 0 : EIO) && command.finished() && state.held == 1024 &&
                clickEdges == std::vector<unsigned>({201, 202}), "admitted click failed to release after error/cancellation");
        require(mode != 1 || clickSteps.empty(), "failed input press entered the GUI gesture");
        require(mode != 3 || clickSteps == std::vector<unsigned>({1, 2, 3, 4, 5}),
                "cancellation after admission dropped finite GUI cleanup");
    }
    reset();
    clickMode = 4;
    request.control = request.shift = request.alt = 1;
    clickModifiers = 7;
    UiClickCommand deferred;
    auto mutableConfig = config;
    auto mutableUi = context.ui;
    require(deferred.start(context.guiInstance, activeGui, mutableUi, mutableConfig, selector, request, &cancel, scratch)
            == ENOENT && !deferred.finished() && (state.held & 4) && clickEdges.size() == 1 &&
            keys[0].held && keys[1].held && keys[2].held,
            "missing cleanup clock dropped input or GUI ownership");
    mutableConfig = {};
    mutableUi = {};
    activeGui->input = clock;
    require(deferred.finish() == ENOENT && deferred.finished() && state.held == 1024 &&
            clickEdges == std::vector<unsigned>({201, 202}) && clickSteps == std::vector<unsigned>({1, 2, 3, 4, 5}) &&
            clickKeyEdges == std::vector<unsigned>({17, 29, 43, 299, 285, 273}) &&
            !keys[0].held && !keys[1].held && !keys[2].held,
            "deferred click did not retain its copied configuration or replayed the click");
    reset();
    clickMode = 4;
    clickModifiers = 7;
    UiClickCommand vanished;
    require(vanished.start(context.guiInstance, activeGui, context.ui, config, selector, request, &cancel, scratch)
            == ENOENT && !vanished.finished(), "vanished-service fixture failed to retain cleanup");
    clearReferences(root.captureTarget);
    clearReferences(child.captureTarget);
    clickGlobal = nullptr;
    const auto beforeVanishing = clickSteps;
    require(vanished.finish() == ENOENT && vanished.finished() && clickEdges.size() == 1 &&
            clickSteps == beforeVanishing && !activeGui->capture.target,
            "vanished input service required an unavailable clock or replayed an event");
    clickGlobal = &global;
    request.control = request.shift = request.alt = 0;
    for (unsigned mode = 0; mode < 4; ++mode) {
        reset();
        auto selected = selector;
        if (mode == 0)
            cancel = 1;
        if (mode == 1)
            selected.path[0].text[0] = 'z';
        if (mode == 2)
            root.flags = 0;
        if (mode == 3)
            state.held |= 4;
        UiClickCommand rejected;
        require(rejected.start(context.guiInstance, activeGui, context.ui, config, selected, request, &cancel, scratch)
                != 0 && rejected.finished() && clickEdges.empty() && clickSteps.empty(),
                "rejected click mutated input or GUI state");
        require(mode != 3 || (state.held & 4), "rejected click released an unrelated held button");
    }
    reset();
    root.flags = rootFlags;
    child.flags = childFlags;
    clickGlobal = nullptr;
    clickCancel = nullptr;
}

struct TextEvent {
    uint64_t marker;
    uint32_t character;
    uint32_t extended;
    void *source;
    uint32_t key;
    uint8_t control;
    double time;
};

static std::vector<TextEvent> textEvents;
static unsigned textMode = 0;
static unsigned textFocusCalls = 0;
static uint32_t *textCancel = nullptr;
static FmLinuxUiTextConfig *textSharedConfig = nullptr;
static FmLinuxUiTextRequest *textSharedRequest = nullptr;

static void textFocus(void *widget, bool tabbed) {
    require(!tabbed, "text focus unexpectedly requested tab navigation");
    ++textFocusCalls;
    if (textMode == 1)
        clearReferences(static_cast<Widget *>(widget)->captureTarget);
    if (textMode == 2)
        throw 1;
    if (textMode == 3)
        *textCancel = 1;
    if (textMode == 7) {
        *textSharedConfig = {};
        *textSharedRequest = {};
    }
}

static bool textKeyDown(void *widget, const void *event) {
    const auto &input = *static_cast<const TextEvent *>(event);
    require(input.source == widget && input.marker == 731 && input.extended == 81 && input.time == activeGui->input->time,
            "text event lost native defaults, source, time or extended key");
    activeGui->input->time += 0.5;
    textEvents.push_back(input);
    if (textEvents.size() == 2) {
        if (textMode == 4)
            clearReferences(static_cast<Widget *>(widget)->captureTarget);
        if (textMode == 5)
            throw 1;
        if (textMode == 6)
            *textCancel = 1;
        if (textMode == 8)
            unlinkMode = 1;
    }
    return false;
}

static void checkText(const UiGestureContext &context, const FmLinuxModalLayout &modal,
                      const FmLinuxUiSelector &selector, FmLinuxUiSnapshot &scratch, Widget &root, Label &child) {
    FmLinuxUiTextConfig original{};
    original.clock = context.clock;
    original.capture = context.capture;
    original.capture.targeter.release = reinterpret_cast<uintptr_t>(&assignCapture);
    original.modal = modal;
    original.dynamicCast = reinterpret_cast<uintptr_t>(&__cxxabiv1::__dynamic_cast);
    original.widgetType = reinterpret_cast<uintptr_t>(&typeid(Widget));
    original.textBoxType = reinterpret_cast<uintptr_t>(&typeid(Label));
    original.focus = reinterpret_cast<uintptr_t>(&textFocus);
    original.keyDown = reinterpret_cast<uintptr_t>(&textKeyDown);
    original.event.extent = sizeof(TextEvent);
    original.event.key = offsetof(TextEvent, key);
    original.event.extended = offsetof(TextEvent, extended);
    original.event.character = offsetof(TextEvent, character);
    original.event.control = offsetof(TextEvent, control);
    original.event.source = offsetof(TextEvent, source);
    original.event.time = offsetof(TextEvent, time);
    original.event.none = 71;
    original.event.selectAll = 72;
    original.event.backspace = 73;
    original.event.extendedNone = 81;
    TextEvent defaults{};
    defaults.marker = 731;
    std::memcpy(original.event.defaults, &defaults, sizeof(defaults));
    const auto rootFlags = root.flags;
    const auto childFlags = child.flags;
    root.flags = child.flags = 8 | 2;
    clockMode = unlinkMode = 0;
    activeGui->modalBegin = activeGui->modalEnd = nullptr;
    for (unsigned mode = 0; mode <= 8; ++mode) {
        textMode = mode;
        textFocusCalls = 0;
        textEvents.clear();
        uint32_t cancel = 0;
        textCancel = &cancel;
        auto config = original;
        FmLinuxUiTextRequest request{3, {65, 0x4e2d, 0x1f600}};
        textSharedConfig = &config;
        textSharedRequest = &request;
        UiTextCommand command;
        const int expected = mode == 1 || mode == 4 ? ENOENT : mode == 2 || mode == 5 || mode == 8 ? EFAULT :
            mode == 3 || mode == 6 ? ECANCELED : 0;
        require(command.start(context.guiInstance, activeGui, context.ui, config, selector, request, &cancel, scratch)
                == expected && textFocusCalls == 1, "text command did not preserve dispatch failure/cancellation");
        const size_t expectedCount = mode >= 1 && mode <= 3 ? 0 : mode >= 4 && mode <= 6 ? 2 : 5;
        require(textEvents.size() == expectedCount, "text command replayed or omitted an editing event");
        if (expectedCount) {
            require(textEvents[0].key == 72 && textEvents[0].control && !textEvents[0].character &&
                    textEvents[1].key == 73 && !textEvents[1].control && !textEvents[1].character,
                    "text replacement did not use select-all and backspace");
            if (expectedCount == 5)
                require(textEvents[2].key == 71 && textEvents[2].character == 65 &&
                        textEvents[3].character == 0x4e2d && textEvents[4].character == 0x1f600 &&
                        !textEvents[2].control && !textEvents[3].control && !textEvents[4].control,
                        "text replacement changed Unicode scalars or retained modifiers");
        }
        require(command.finished() == (mode != 8), "text command discarded incomplete reference cleanup");
        unlinkMode = 0;
        config = {};
        request = {};
        require(command.finish() == expected && command.finished() && textEvents.size() == expectedCount &&
                !root.captureTarget.head && !child.captureTarget.head,
                "text cleanup replayed edits, lost the first error or used overwritten configuration");
    }
    textMode = 0;
    textEvents.clear();
    textFocusCalls = 0;
    uint32_t cancel = 0;
    FmLinuxUiTextRequest request{};
    UiTextCommand empty;
    require(empty.start(context.guiInstance, activeGui, context.ui, original, selector, request, &cancel, scratch) == 0 &&
            textEvents.size() == 2 && empty.finished(), "empty text did not dispatch clearing events");
    for (unsigned bad = 0; bad < 5; ++bad) {
        auto config = original;
        request = {};
        if (bad == 0)
            request.count = FM_LINUX_TEXT_SCALARS + 1;
        if (bad == 1) {
            request.count = 1;
            request.text[0] = 0xd800;
        }
        if (bad == 2) {
            request.count = 1;
            request.text[0] = 0x110000;
        }
        if (bad == 3)
            config.event.time = config.event.source;
        if (bad == 4)
            config.textBoxType = reinterpret_cast<uintptr_t>(&typeid(Unknown));
        UiTextCommand command;
        require(command.start(context.guiInstance, activeGui, context.ui, config, selector, request, &cancel, scratch)
                == EINVAL && command.finished() && textFocusCalls == 1 && textEvents.size() == 2,
                "invalid text request/layout/type entered editing callbacks");
    }
    root.flags = rootFlags;
    child.flags = childFlags;
}

struct PollKeyEvent {
    uint64_t marker = 731;
    uint32_t type = 77;
    double time = 0;
    uint32_t code = 0;
    uint32_t character = 99;
    uint8_t repeat = 99;
};

static bool keyClockFailure;
static uint32_t keyTicks() {
    if (keyClockFailure)
        throw 1;
    return 123456;
}

static void keyFocus(void *widget, bool tabbed) {
    textFocus(widget, tabbed);
    if (textMode == 8)
        unlinkMode = 1;
}

static void checkKeys(const UiGestureContext &context, const FmLinuxModalLayout &modal,
                      const FmLinuxUiSelector &selector, FmLinuxUiSnapshot &scratch, Widget &root, Label &child) {
    FmLinuxUiKeyConfig original{};
    original.event.extent = sizeof(PollKeyEvent);
    original.event.type = offsetof(PollKeyEvent, type);
    original.event.time = offsetof(PollKeyEvent, time);
    original.event.code = offsetof(PollKeyEvent, code);
    original.event.emptyType = 77;
    original.event.press = 19;
    original.event.release = 39;
    for (unsigned byte = offsetof(PollKeyEvent, character); byte < offsetof(PollKeyEvent, character) + 4; ++byte)
        original.event.initialized[byte] = 1;
    original.event.initialized[offsetof(PollKeyEvent, repeat)] = 1;
    original.clock = {reinterpret_cast<uintptr_t>(&keyTicks), 1000};
    original.capture = context.capture;
    original.capture.targeter.release = reinterpret_cast<uintptr_t>(&assignCapture);
    original.modal = modal;
    original.focus = reinterpret_cast<uintptr_t>(&keyFocus);
    uint32_t cancel = 0;
    textCancel = &cancel;
    FmLinuxUiSelector none{};
    const FmLinuxUiKeyRequest input{2, {11, 17}};
    for (int canceledAt = -1; canceledAt <= 4; ++canceledAt) {
        auto config = original;
        auto request = input;
        UiKeyCommand command;
        require(command.start(0, nullptr, {}, config, none, request, &cancel, scratch) == 0 && command.active(),
                "key admission did not retain a finite gesture");
        config = {};
        request = {};
        std::vector<uint32_t> held;
        unsigned edge = 0;
        while (command.active()) {
            PollKeyEvent event;
            bool produced = false;
            require(command.poll(&event, canceledAt >= 0 && static_cast<int>(edge) >= canceledAt, produced) == 0,
                    "key dispatch failed after wire payload overwrite");
            if (produced) {
                require(event.marker == 731 && event.time == 123.456 && !event.character && !event.repeat,
                        "key event lost its timestamp, defaults or untouched bytes");
                if (event.type == 19) {
                    require(canceledAt < 0 || static_cast<int>(edge) < canceledAt, "cancel admitted another key down");
                    held.push_back(event.code);
                } else {
                    require(event.type == 39 && !held.empty() && held.back() == event.code, "key up was omitted or reordered");
                    held.pop_back();
                }
                require(++edge <= 4, "finite key gesture exceeded its bound");
            }
        }
        require(held.empty() && command.finished() && command.failure() == (canceledAt < 0 ? 0 : ECANCELED),
                "key cleanup lost held state or cancellation result");
        require(command.finish() == command.failure(), "repeat key cleanup changed the original result");
    }
    for (bool clockFailure : {false, true}) {
        UiKeyCommand command;
        require(command.start(0, nullptr, {}, original, none, input, &cancel, scratch) == 0, "key retry fixture admission failed");
        PollKeyEvent event;
        bool produced;
        require(command.poll(&event, false, produced) == 0 && produced && event.type == 19 && event.code == 11,
                "key retry fixture did not dispatch its first down");
        event = {};
        if (!clockFailure)
            event.type = 123;
        keyClockFailure = clockFailure;
        const int error = clockFailure ? EIO : ESTALE;
        require(command.poll(&event, false, produced) == error && !produced && command.active() && !command.finished(),
                "failed key write lost an owed release");
        keyClockFailure = false;
        require(command.finish() == error && command.active(), "key cleanup lost its first error");
        event = {};
        require(command.poll(&event, false, produced) == 0 && produced && event.type == 39 && event.code == 11,
                "key cleanup replayed a down or skipped its up");
        event = {};
        require(command.poll(&event, false, produced) == 0 && !produced && command.finished() && command.failure() == error,
                "key cleanup finished before acknowledgement or lost its error");
    }
    const auto rootFlags = root.flags, childFlags = child.flags;
    root.flags = child.flags = 8 | 2;
    activeGui->modalBegin = activeGui->modalEnd = nullptr;
    for (unsigned mode : {0u, 1u, 2u, 3u, 8u}) {
        textMode = mode;
        textFocusCalls = 0;
        cancel = 0;
        UiKeyCommand command;
        const int expected = mode == 1 ? ENOENT : mode == 2 || mode == 8 ? EFAULT : mode == 3 ? ECANCELED : 0;
        require(command.start(context.guiInstance, activeGui, context.ui, original, selector, input, &cancel, scratch) == expected &&
                textFocusCalls == 1 && command.active() == (mode == 0), "selected key focus changed admission or lifetime semantics");
        if (mode == 8)
            require(!command.finished(), "key focus discarded failed reference cleanup");
        unlinkMode = 0;
        const int result = command.finish();
        if (command.active()) {
            PollKeyEvent event;
            bool produced;
            require(command.poll(&event, false, produced) == 0 && !produced, "canceled focus admitted a key edge");
        }
        require(command.finished() && result == (mode == 0 ? ECANCELED : expected) &&
                !root.captureTarget.head && !child.captureTarget.head, "key focus cleanup leaked references or changed error");
    }
    cancel = 0;
    textMode = 0;
    root.flags = rootFlags;
    child.flags = childFlags;
}

int main() {
    alarm(30);
    Widget root;
    Label child;
    Widget other;
    Widget *first[] = {&child};
    Widget *second[] = {&other};
    root.first = first;
    root.last = first + 1;
    root.privateFirst = second;
    root.privateLast = second + 1;
    root.flags = 0;
    other.flags = 9;
    root.text = {0, "root", 4};
    root.bounds = {-12, 7, 200, 300};
    child.parent = &root;
    child.bounds = {3, -21, 19, 31};
    child.label = {0, "label", 5};
    Gui gui{{}, &root};
    auto offset = [](const void *object, const void *member) {
        return static_cast<uint32_t>(static_cast<const char *>(member) - static_cast<const char *>(object));
    };
    FmLinuxUiLayout layout{};
    layout.guiSize = sizeof(gui);
    layout.widgetSize = sizeof(root);
    layout.root = offset(&gui, &gui.root);
    layout.rangeCount = 2;
    layout.ranges[0] = {offset(&root, &root.first), offset(&root, &root.last)};
    layout.ranges[1] = {offset(&root, &root.privateFirst), offset(&root, &root.privateLast)};
    layout.enabled = {offset(&root, &root.flags), 1, 8, 3};
    layout.destroying = {offset(&root, &root.flags), 1, 1, 0};
    layout.visible = {offset(&root, &root.flags), 1, 2, 1};
    layout.hiddenBySearch = {offset(&root, &root.flags), 1, 4, 2};
    layout.renderEnabled = {offset(&root, &root.flags), 1, 16, 4};
    layout.rectangle = {reinterpret_cast<uintptr_t>(&rectangle), offset(&root, &root.parent)};
    const auto method = &Widget::getText;
    std::array<ptrdiff_t, 2> member{};
    static_assert(sizeof(method) == sizeof(member));
    std::memcpy(member.data(), &method, sizeof(method));
    require(member[0] > 0 && member[0] % sizeof(uintptr_t) == 1 && member[1] == 0, "unexpected fixture member pointer");
    layout.text.slot = (member[0] - 1) / sizeof(uintptr_t);
    layout.text.data = offsetof(Text, data);
    layout.text.length = offsetof(Text, length);
    layout.text.count = 2;
    auto getter = [&](const Widget &object) {
        return (*reinterpret_cast<const uintptr_t *const *>(&object))[layout.text.slot];
    };
    layout.text.getters[0] = {getter(root), offset(&root, &root.text), sizeof(root)};
    layout.text.getters[1] = {getter(child), offset(&child, &child.label), sizeof(child)};
    auto output = std::make_unique<FmLinuxUiSnapshot>();
    uint32_t cancel = 0;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0, "snapshot failed");
    layout.toggle = {(*reinterpret_cast<uintptr_t **>(&root))[3], 3,
        offset(&root, &root.toggled), sizeof(Widget)};
    layout.toggle.modeOffset = offset(&root, &root.toggleMode);
    layout.toggle.tableCount = 2;
    layout.toggle.tables[0] = *reinterpret_cast<uintptr_t *>(&root);
    layout.toggle.tables[1] = *reinterpret_cast<uintptr_t *>(&child);
    root.toggled = 1;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && output->nodes[0].toggledAvailable &&
        output->nodes[0].toggled == 1 && output->nodes[1].toggledAvailable && !output->nodes[1].toggled,
        "toggle getter did not expose each widget's own byte");
    root.toggleMode = 0;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && !output->nodes[0].toggledAvailable &&
        output->nodes[1].toggledAvailable, "ordinary button exposed toggle state");
    root.toggleMode = 2;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EPROTO, "invalid toggle mode was normalized");
    root.toggleMode = 1;
    root.toggled = 2;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EPROTO, "invalid boolean byte was normalized");
    ++layout.toggle.function;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && !output->nodes[0].toggledAvailable,
        "unknown virtual getter invented toggle state");
    layout.toggle.offset = sizeof(Widget);
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EINVAL, "toggle member bounds were ignored");
    root.toggled = 0;
    layout.toggle = {};
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0, "snapshot failed after toggle checks");
    layout.checkCount = 1;
    layout.checks[0] = {*reinterpret_cast<uintptr_t *>(&root), offset(&root, &root.checkState), sizeof(Widget)};
    for (int32_t value : {0, 1, 2, -7, INT32_MAX}) {
        root.checkState = value;
        require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && output->nodes[0].checkAvailable &&
            output->nodes[0].checkState == value && !output->nodes[1].checkAvailable,
            "check state was normalized or read from an unverified concrete table");
    }
    layout.checks[1] = layout.checks[0];
    layout.checkCount = 2;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EINVAL, "ambiguous check layouts accepted");
    layout.checkCount = 1;
    layout.checks[0].offset = sizeof(Widget) - 3;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EINVAL, "check state exceeded object bounds");
    layout.checkCount = 0;
    layout.sliderCount = 1;
    layout.sliders[0] = {*reinterpret_cast<uintptr_t *>(&root), sizeof(Widget),
        offset(&root, &root.value), offset(&root, &root.minimum),
        offset(&root, &root.maximum), offset(&root, &root.step)};
    root.value = -1.25;
    root.minimum = 2.5;
    root.maximum = -3.75;
    root.step = -0.0;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && output->nodes[0].sliderAvailable &&
        !output->nodes[1].sliderAvailable && output->nodes[0].value == -1.25 &&
        output->nodes[0].minimum == 2.5 && output->nodes[0].maximum == -3.75 &&
        std::signbit(output->nodes[0].step), "slider fields were normalized or concrete identity ignored");
    root.value = std::numeric_limits<double>::quiet_NaN();
    root.minimum = -std::numeric_limits<double>::infinity();
    root.maximum = std::numeric_limits<double>::infinity();
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && std::isnan(output->nodes[0].value) &&
        output->nodes[0].minimum == root.minimum && output->nodes[0].maximum == root.maximum,
        "non-finite slider fields were lost");
    layout.sliders[1] = layout.sliders[0];
    layout.sliderCount = 2;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EINVAL, "duplicate slider layouts accepted");
    layout.sliderCount = 1;
    layout.sliders[0].step = sizeof(Widget) - 7;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EINVAL, "slider field exceeded object bounds");
    layout.sliders[0].step = layout.sliders[0].value + 1;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EINVAL, "overlapping slider fields accepted");
    layout.sliderCount = 0;
    layout.progressCount = 1;
    layout.progress[0] = {*reinterpret_cast<uintptr_t *>(&root), sizeof(Widget), offset(&root, &root.value),
        offset(&root, &root.progressDirection), offset(&root, &root.progressHasText)};
    root.value = -2.5;
    root.progressDirection = 255;
    root.progressHasText = 1;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && output->nodes[0].progressAvailable &&
        !output->nodes[1].progressAvailable && output->nodes[0].progressValue == -2.5 &&
        output->nodes[0].progressDirection == 255 && output->nodes[0].progressHasText == 1,
        "progress values were normalized or concrete identity ignored");
    root.value = std::numeric_limits<double>::infinity();
    root.progressHasText = 0;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 &&
        output->nodes[0].progressValue == root.value && output->nodes[0].progressHasText == 0,
        "raw non-finite progress or false text flag lost");
    root.progressHasText = 2;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EPROTO, "invalid progress boolean accepted");
    root.progressHasText = 0;
    layout.progress[1] = layout.progress[0];
    layout.progressCount = 2;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EINVAL, "duplicate progress layouts accepted");
    layout.progressCount = 1;
    layout.progress[0].hasText = layout.progress[0].direction;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EINVAL, "overlapping progress flags accepted");
    layout.progress[0].hasText = layout.progress[0].value + 7;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EINVAL, "progress flag overlaps value");
    layout.progress[0].hasText = sizeof(Widget);
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EINVAL, "progress flag exceeds object bounds");
    layout.progressCount = 0;
    {
        layout.dropdownCount = 1;
        layout.dropdowns[0] = {*reinterpret_cast<uintptr_t *>(&root), sizeof(Widget),
            offset(&root, &root.selectedIndex), offset(&root, &root.optionFirst), offset(&root, &root.optionLast),
            sizeof(Option), offsetof(Option, button)};
        Label empty, longLabel;
        const std::string caption = std::string(510, 'x') + "界";
        longLabel.label = {0, caption.data(), caption.size()};
        std::array<Option, 65> entries{};
        for (auto &entry : entries)
            entry.button = &child;
        entries[0].button = &empty;
        entries[1].button = &longLabel;
        root.optionFirst = entries.data();
        root.optionLast = entries.data() + entries.size();
        root.selectedIndex = 80;
        require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && output->nodes[0].dropdownAvailable &&
            output->nodes[0].selectedIndex == 80 && output->nodes[0].optionsAvailable &&
            output->nodes[0].optionTotal == 65 && output->nodes[0].optionCount == 64 && output->optionCount == 64 &&
            output->options[0].size == 0 && !output->options[0].truncated && output->options[1].size == 510 &&
            output->options[1].truncated && !output->nodes[1].dropdownAvailable,
            "dropdown selection, empty labels, UTF-8 boundary or per-widget limit changed");
        Unknown unsupported;
        entries[1].button = &unsupported;
        require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && output->nodes[0].dropdownAvailable &&
            !output->nodes[0].optionsAvailable && !output->optionCount,
            "unknown option getter became an empty label or consumed the snapshot budget");
        entries[1].button = &longLabel;
        root.optionLast = reinterpret_cast<Option *>(reinterpret_cast<uintptr_t>(root.optionFirst) + 1);
        require(snapshotUi(&gui, layout, 10, &cancel, *output) == EPROTO, "misaligned option range accepted");
        root.optionLast = reinterpret_cast<Option *>(reinterpret_cast<uintptr_t>(root.optionFirst) + sizeof(Option) * 65537);
        require(snapshotUi(&gui, layout, 10, &cancel, *output) == EPROTO, "oversized option range accepted");
        root.optionLast = root.optionFirst;
        root.selectedIndex = -1;
        require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && output->nodes[0].selectedIndex == -1 &&
            output->nodes[0].optionsAvailable && !output->nodes[0].optionCount, "empty dropdown became unavailable");
        std::array<Widget, 18> many{};
        std::array<Widget *, 17> children{};
        for (size_t i = 0; i < many.size(); ++i) {
            many[i].optionFirst = entries.data();
            many[i].optionLast = entries.data() + entries.size();
            if (i) children[i - 1] = &many[i];
        }
        many[0].first = children.data();
        many[0].last = children.data() + children.size();
        many.back().text = {0, "chosen", 6};
        gui.root = &many[0];
        require(snapshotUi(&gui, layout, 32, &cancel, *output) == 0 && output->optionCount == 1024 &&
            output->nodes[16].optionsAvailable && output->nodes[16].optionTotal == 65 &&
            output->nodes[16].optionCount == 0, "snapshot option budget was exceeded or total was lost");
        FmLinuxUiSelector chosen{};
        chosen.count = 1;
        chosen.path[0].enabled = -1;
        chosen.path[0].hasText = 1;
        std::memcpy(chosen.path[0].text, "chosen", 7);
        many[0].optionFirst = reinterpret_cast<Option *>(1);
        many[0].optionLast = reinterpret_cast<Option *>(1 + 65 * sizeof(Option));
        require(snapshotUi(&gui, layout, 32, &cancel, *output, &chosen) == 0 && output->optionCount == 64 &&
            output->nodes[17].optionCount == 64 && !output->nodes[0].dropdownAvailable,
            "unrelated dropdowns consumed selected subtree budget or prevented its observation");
        gui.root = &root;
        root.optionFirst = root.optionLast = nullptr;
        const auto valid = layout.dropdowns[0];
        layout.dropdowns[0].last = valid.first;
        require(snapshotUi(&gui, layout, 10, &cancel, *output) == EINVAL, "overlapping dropdown pointers accepted");
        layout.dropdowns[0] = valid;
        layout.dropdowns[0].button = valid.stride - 7;
        require(snapshotUi(&gui, layout, 10, &cancel, *output) == EINVAL, "option pointer exceeded element bounds");
        layout.dropdowns[0] = valid;
        layout.dropdowns[1] = valid;
        layout.dropdownCount = 2;
        require(snapshotUi(&gui, layout, 10, &cancel, *output) == EINVAL, "ambiguous dropdown identity accepted");
        layout.dropdownCount = 0;
    }
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0, "snapshot failed after check-state tests");
    require(output->count == 3 && !output->truncated, "children were dropped or duplicated");
    require(output->nodes[0].parent == -1 && output->nodes[1].parent == 0 && output->nodes[2].parent == 0,
            "parent relationships changed");
    require(output->nodes[0].depth == 0 && output->nodes[1].depth == 1, "incorrect depth");
    const auto &bounds = output->nodes[1].bounds;
    require(bounds.x == -9 && bounds.y == -14 && bounds.width == 19 && bounds.height == 31,
            "native rectangle return registers or signed fields were lost");
    root.parent = &child;
    rectangleCalls = 0;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == ELOOP && !rectangleCalls,
            "cyclic parents entered the unbounded native getter");
    root.parent = nullptr;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0, "cannot read after rejecting parent cycle");
    auto ancestors = std::make_unique<std::array<Widget, 256>>();
    for (size_t index = 1; index < ancestors->size(); ++index)
        (*ancestors)[index - 1].parent = &(*ancestors)[index];
    root.parent = &(*ancestors)[0];
    rectangleCalls = 0;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == E2BIG && !rectangleCalls,
            "parent-chain work bound was ignored");
    root.parent = nullptr;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0, "cannot read after rejecting parent depth");
    require(!output->nodes[0].enabled && output->nodes[1].enabled && output->nodes[2].destroying,
            "widget flags were inferred from their parent or dropped");
    child.flags |= 2 | 4;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && output->nodes[1].visible &&
            output->nodes[1].hiddenBySearch && !output->nodes[0].visible,
            "own visibility was replaced by parent or search state");
    child.flags |= 16;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && output->nodes[1].renderEnabled &&
            !output->nodes[0].renderEnabled && output->nodes[1].hiddenBySearch,
            "own render flag was replaced by parent or search state");
    child.flags &= ~16;
    root.flags |= 16;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && !output->nodes[1].renderEnabled &&
            output->nodes[0].renderEnabled && output->nodes[1].visible,
            "parent render flag propagated to child or changed own visibility");
    root.flags &= ~16;
    require(std::strcmp(output->nodes[1].type, "Label") == 0, "dynamic type was lost");
    require(output->nodes[1].textAvailable && output->nodes[1].textSize == 5 &&
            std::memcmp(output->nodes[1].text, "label", 5) == 0, "derived text getter was ignored");
    require(output->nodes[2].textAvailable && output->nodes[2].textTotal == 0, "empty string became unavailable");
    FmLinuxUiSelector selector{};
    selector.count = 1;
    selector.path[0].enabled = -1;
    std::memcpy(selector.path[0].type, "Label", 6);
    rectangleCalls = 0;
    require(snapshotUi(&gui, layout, 10, &cancel, *output, &selector) == 0 && output->nodes[1].selected &&
            !output->nodes[0].selected && !output->nodes[2].selected && rectangleCalls == 1,
            "selection did not precede optional rectangle reads");
    selector.path[0].hasVisible = 1;
    selector.path[0].visible = 0;
    require(snapshotUi(&gui, layout, 10, &cancel, *output, &selector) == 0 && !output->nodes[1].selected,
            "visibility predicate ignored the widget's own flag");
    selector.path[0].visible = 1;
    require(snapshotUi(&gui, layout, 10, &cancel, *output, &selector) == 0 && output->nodes[1].selected,
            "search-hidden or parent flags changed visible matching");
    other.parent = &other;
    require(snapshotUi(&gui, layout, 10, &cancel, *output, &selector) == 0,
            "unrelated optional geometry prevented selected observation");
    other.parent = nullptr;
    rectangleCalls = 0;
    require(snapshotUi(&gui, layout, 1, &cancel, *output, &selector) == EOVERFLOW && output->treeTruncated && !rectangleCalls,
            "incomplete selector search collected optional properties");
    selector.path[0].hasText = 1;
    std::memcpy(selector.path[0].text, "label", 6);
    require(snapshotUi(&gui, layout, 10, &cancel, *output, &selector) == 0 && output->nodes[1].selected,
            "selected native text did not match");
    UiTarget target;
    require(selectUiTarget(&gui, layout, selector, &cancel, *output, target) == EACCES && !target.widget,
            "action admitted a disabled ancestor");
    root.flags = 8;
    require(selectUiTarget(&gui, layout, selector, &cancel, *output, target) == 0 &&
            target.gui == reinterpret_cast<uintptr_t>(&gui) && target.root == reinterpret_cast<uintptr_t>(&root) &&
            target.widget == reinterpret_cast<uintptr_t>(&child) && target.bounds.x == -9 && target.bounds.y == -14,
            "action did not resolve its unique target and bounds");
    activeGui = &gui;
    const auto instance = reinterpret_cast<uintptr_t>(&activeGui);
    auto rootSelector = selector;
    rootSelector.path[0].type[0] = 0;
    rootSelector.path[0].hasVisible = 0;
    std::memcpy(rootSelector.path[0].text, "root", 5);
    UiTarget rootTarget;
    child.parent = &child;
    rectangleCalls = 0;
    require(selectUiTarget(&gui, layout, rootSelector, &cancel, *output, rootTarget) == 0 &&
            rootTarget.widget == reinterpret_cast<uintptr_t>(&root) && rectangleCalls == 1,
            "action read optional geometry from an unselected descendant");
    child.parent = &root;
    rectangleCalls = 0;
    require(liveUiTarget(instance, layout, target) == 0 && !rectangleCalls,
            "liveness invoked an optional getter");
    child.flags |= 1;
    require(liveUiTarget(instance, layout, target) == ENOENT, "destroying widget remained actionable");
    child.flags &= ~uint8_t{1};
    first[0] = &other;
    require(liveUiTarget(instance, layout, target) == ENOENT, "removed widget remained actionable");
    first[0] = &child;
    gui.root = &other;
    require(liveUiTarget(instance, layout, target) == ESTALE, "replaced root was retained");
    gui.root = &root;
    activeGui = nullptr;
    require(liveUiTarget(instance, layout, target) == ESTALE, "destroyed GUI was dereferenced");
    activeGui = &gui;
    FmLinuxMouseEventLayout eventLayout{sizeof(MouseEvent), offsetof(MouseEvent, x), offsetof(MouseEvent, y),
        offsetof(MouseEvent, source), offsetof(MouseEvent, wheel), offsetof(MouseEvent, button), offsetof(MouseEvent, type),
        offsetof(MouseEvent, time), offsetof(MouseEvent, alt), offsetof(MouseEvent, control), offsetof(MouseEvent, shift)};
    const uintptr_t clockTable[] = {0, reinterpret_cast<uintptr_t>(&inputTime)};
    InputClock input{clockTable, 123.25};
    gui.input = &input;
    FmLinuxInputClockLayout clockLayout{sizeof(Gui), offsetof(Gui, input), sizeof(InputClock), 1,
        reinterpret_cast<uintptr_t>(clockTable), reinterpret_cast<uintptr_t>(&inputTime)};
    UiMouseRequest request;
    request.type = 29;
    request.button = 64;
    request.x = 1;
    request.y = 0.5;
    request.alt = true;
    request.shift = true;
    UiMouseDispatch progress;
    require(dispatchUiMouse(instance, layout, eventLayout, clockLayout, mouseEvent, target, request, progress) == 0 &&
            progress.entered && progress.returned && !progress.targetStatus && dispatchCalls == 1,
            "single native event dispatch failed or replayed");
    require(received.x == 18 && received.y == 15 && received.type == 29 && received.button == 64 &&
            received.time == 123.25 && received.alt && !received.control && received.shift,
            "event fields, coordinates or modifier bytes were changed");
    replacement = &other;
    for (unsigned mode = 1; mode <= 5; ++mode) {
        dispatchMode = mode;
        const unsigned before = dispatchCalls;
        const int expected = mode == 3 || mode == 4 ? EIO : mode == 5 ? ELOOP : 0;
        require(dispatchUiMouse(instance, layout, eventLayout, clockLayout, mouseEvent, target, request, progress) == expected &&
                progress.entered && progress.returned == (mode != 3 && mode != 4) && dispatchCalls == before + 1,
                "callback progress or first failure was lost");
        require(progress.targetStatus == (mode == 1 || mode == 4 ? ENOENT : mode == 2 ? ESTALE : mode == 5 ? ELOOP : 0),
                "callback lifetime was not rechecked");
        gui.root = &root;
        first[0] = &child;
    }
    dispatchMode = 0;
    const unsigned before = dispatchCalls;
    first[0] = &other;
    require(dispatchUiMouse(instance, layout, eventLayout, clockLayout, mouseEvent, target, request, progress) == ENOENT &&
            !progress.entered && dispatchCalls == before, "stale target reached a native callback");
    first[0] = &child;
    request.x = std::numeric_limits<double>::quiet_NaN();
    require(dispatchUiMouse(instance, layout, eventLayout, clockLayout, mouseEvent, target, request, progress) == EINVAL &&
            !progress.entered, "invalid fraction reached a native callback");
    request.x = 0.5;
    auto badEvent = eventLayout;
    badEvent.source = eventLayout.type;
    require(dispatchUiMouse(instance, layout, badEvent, clockLayout, mouseEvent, target, request, progress) == EINVAL &&
            !progress.entered, "overlapping fields reached a native callback");
    for (double time : {std::numeric_limits<double>::quiet_NaN(), std::numeric_limits<double>::infinity(), -1.0}) {
        input.time = time;
        require(dispatchUiMouse(instance, layout, eventLayout, clockLayout, mouseEvent, target, request, progress) == ERANGE &&
                !progress.entered, "invalid native clock reached a widget callback");
    }
    input.time = 125.5;
    require(dispatchUiMouse(instance, layout, eventLayout, clockLayout, mouseEvent, target, request, progress) == 0 &&
            received.time == 125.5, "native timestamp was cached or replaced");
    for (unsigned mode = 1; mode <= 2; ++mode) {
        clockMode = mode;
        const auto beforeClock = clockCalls;
        const auto beforeDispatch = dispatchCalls;
        require(dispatchUiMouse(instance, layout, eventLayout, clockLayout, mouseEvent, target, request, progress) ==
                (mode == 1 ? ESTALE : EIO) && !progress.entered && clockCalls == beforeClock + 1 &&
                dispatchCalls == beforeDispatch, "clock callback failure or root change was ignored");
        gui.root = &root;
    }
    clockMode = 0;
    FmLinuxModalLayout modalLayout{sizeof(Gui), sizeof(Widget), offsetof(Gui, modalBegin), offsetof(Gui, modalEnd),
        sizeof(ModalEntry), offsetof(ModalEntry, target), offset(&root, &root.captureTarget), layout.rectangle.parent};
    require(validModalLayout(modalLayout, layout), "compiler modal layout rejected");
    require(checkUiModal(instance, layout, modalLayout, target) == 0, "empty null modal stack blocked action");
    ModalEntry modals[4]{};
    modals[0].target = &root.captureTarget;
    modals[2].target = &child.captureTarget;
    gui.modalBegin = modals;
    gui.modalEnd = modals + 4;
    const auto beforeModalGeometry = rectangleCalls;
    require(checkUiModal(instance, layout, modalLayout, target) == 0 && gui.modalEnd == modals + 4 &&
            rectangleCalls == beforeModalGeometry, "modal reader pruned records or invoked geometry");
    require(checkUiModal(instance, layout, modalLayout, rootTarget) == EACCES,
            "lower modal ancestor bypassed the top modal");
    UiTarget admitted;
    require(selectUiActionTarget(instance, &gui, layout, modalLayout, selector, &cancel, *output, admitted) == 0 &&
            admitted.widget == target.widget, "action admission lost a valid selected modal child");
    require(selectUiActionTarget(instance, &gui, layout, modalLayout, rootSelector, &cancel, *output, admitted) == EACCES &&
            !admitted.widget, "modal rejection retained an actionable pointer");
    modals[2].target = nullptr;
    require(checkUiModal(instance, layout, modalLayout, target) == 0 && gui.modalEnd == modals + 4,
            "descendant of the remaining modal was rejected or empty entries were removed");
    const auto savedOtherFlags = other.flags;
    other.flags &= ~uint8_t{1};
    UiTarget otherTarget = target;
    otherTarget.widget = reinterpret_cast<uintptr_t>(&other);
    require(checkUiModal(instance, layout, modalLayout, otherTarget) == EACCES,
            "unrelated widget bypassed modal ancestry");
    other.parent = &child;
    require(checkUiModal(instance, layout, modalLayout, otherTarget) == 0,
            "native parent chain was replaced with direct-child-only modal matching");
    other.parent = nullptr;
    other.flags = savedOtherFlags;
    modals[0].target = nullptr;
    require(checkUiModal(instance, layout, modalLayout, target) == 0 && gui.modalEnd == modals + 4,
            "all-null modal entries blocked the UI or were pruned");
    Widget absentModal;
    modals[3].target = &absentModal.captureTarget;
    require(checkUiModal(instance, layout, modalLayout, target) == ENOENT,
            "stale modal target was followed outside the complete UI traversal");
    modals[3].target = &child.captureTarget;
    child.flags |= 1;
    require(checkUiModal(instance, layout, modalLayout, rootTarget) == ENOENT,
            "destroying modal target remained valid");
    child.flags &= ~uint8_t{1};
    child.parent = &child;
    require(checkUiModal(instance, layout, modalLayout, target) == ELOOP,
            "a matching modal skipped validation of its cyclic parent chain");
    child.parent = &root;
    modals[3].target = reinterpret_cast<CaptureTarget *>(1);
    require(checkUiModal(instance, layout, modalLayout, target) == EFAULT,
            "underflowing targetable conversion was accepted");
    modals[3].target = nullptr;
    gui.modalEnd = reinterpret_cast<ModalEntry *>(reinterpret_cast<uintptr_t>(modals) + 1);
    require(checkUiModal(instance, layout, modalLayout, target) == EFAULT, "partial modal record was accepted");
    gui.modalEnd = reinterpret_cast<ModalEntry *>(reinterpret_cast<uintptr_t>(modals) +
        (FM_LINUX_MAX_NODES + 1ULL) * sizeof(ModalEntry));
    require(checkUiModal(instance, layout, modalLayout, target) == E2BIG, "modal record count exceeded its bound");
    gui.modalEnd = modals + 4;
    gui.modalBegin = nullptr;
    require(checkUiModal(instance, layout, modalLayout, target) == EFAULT, "one-null vector endpoint was accepted");
    gui.modalBegin = modals;
    gui.root = &other;
    require(checkUiModal(instance, layout, modalLayout, target) == ESTALE, "modal admission reused a replaced root");
    gui.root = &root;
    auto badModal = modalLayout;
    badModal.parent += 8;
    require(!validModalLayout(badModal, layout), "modal and geometry parent layouts disagree");
    badModal = modalLayout;
    badModal.target = badModal.stride;
    require(!validModalLayout(badModal, layout), "modal target exceeds its record");
    badModal = modalLayout;
    ++badModal.stride;
    require(!validModalLayout(badModal, layout), "unaligned modal record stride accepted");
    const size_t modalPageSize = sysconf(_SC_PAGESIZE);
    void *modalGuard = mmap(nullptr, modalPageSize, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    require(modalGuard != MAP_FAILED, "cannot allocate modal guard page");
    gui.modalBegin = static_cast<ModalEntry *>(modalGuard);
    gui.modalEnd = gui.modalBegin + 1;
    require(checkUiModal(instance, layout, modalLayout, target) == EFAULT, "unreadable modal record was accepted");
    gui.modalBegin = modals;
    gui.modalEnd = modals + 4;
    modals[0].target = &root.captureTarget;
    child.parent = static_cast<Widget *>(modalGuard);
    require(checkUiModal(instance, layout, modalLayout, target) == EFAULT, "unreadable parent was followed");
    child.parent = &root;
    require(munmap(modalGuard, modalPageSize) == 0, "cannot release modal guard page");
    gui.modalBegin = nullptr;
    gui.modalEnd = nullptr;
    FmLinuxCaptureLayout captureLayout{};
    captureLayout.guiSize = sizeof(Gui);
    captureLayout.widgetSize = sizeof(Widget);
    captureLayout.widgetTargetable = offset(&root, &root.captureTarget);
    captureLayout.targeterMember = offsetof(Gui, capture);
    captureLayout.targeter = {reinterpret_cast<uintptr_t>(&unlinkCapture), sizeof(CaptureLink), sizeof(CaptureTarget),
        offsetof(CaptureLink, target), offsetof(CaptureLink, previous), offsetof(CaptureLink, next),
        offsetof(CaptureTarget, head)};
    captureLayout.resetCount = 2;
    captureLayout.resets[0] = {offsetof(Gui, dragging), 1, 0};
    captureLayout.resets[1] = {offsetof(Gui, origin), 8, 0x7fffffff7fffffffULL};
    require(validCaptureLayout(captureLayout, layout), "compiler capture layout rejected");
    {
        auto referenceLayout = captureLayout;
        referenceLayout.targeter.release = reinterpret_cast<uintptr_t>(&assignCapture);
        UiTargetReference reference;
        UiTarget borrowed;
        require(reference.attach(instance, layout, referenceLayout, target) == 0 && reference.owned(),
                "UI reference registration failed");
        require(reference.borrow(instance, layout, borrowed) == 0 && borrowed.widget == target.widget &&
                borrowed.reference == &reference, "UI reference did not reacquire its selected widget");
        auto previousReplacement = replacement;
        replacement = &child;
        clockMode = 5;
        const auto dispatchedBeforeInvalidation = dispatchCalls;
        require(dispatchUiMouseAt(instance, layout, eventLayout, clockLayout, mouseEvent, borrowed, request,
                                 0, 0, progress) == ENOENT && !progress.entered &&
                dispatchCalls == dispatchedBeforeInvalidation,
                "clock callback invalidation dispatched to an object reusing the target address");
        clockMode = 0;
        replacement = previousReplacement;
        require(reference.release() == 0 && reference.attach(instance, layout, referenceLayout, target) == 0 &&
                reference.borrow(instance, layout, borrowed) == 0, "cannot renew the invalidated reference");
        dispatchMode = 6;
        require(dispatchUiMouseAt(instance, layout, eventLayout, clockLayout, mouseEvent, borrowed, request,
                                 0, 0, progress) == 0 && progress.entered && progress.returned &&
                progress.targetStatus == ENOENT, "dispatch callback invalidation retained the original target");
        dispatchMode = 0;
        require(reference.release() == 0 && reference.attach(instance, layout, referenceLayout, target) == 0 &&
                reference.borrow(instance, layout, borrowed) == 0, "cannot renew the dispatched reference");
        require(reference.attach(instance, layout, referenceLayout, target) == EALREADY,
                "owned UI reference was overwritten");
        gui.root = &other;
        require(reference.borrow(instance, layout, borrowed) == ESTALE && !borrowed.widget,
                "UI reference followed a replacement root");
        gui.root = &root;
        require(reference.borrow(instance, layout, borrowed) == 0, "restored original root was rejected");
        clearReferences(child.captureTarget);
        require(liveUiTarget(instance, layout, borrowed) == ENOENT,
                "borrowed target survived native lifetime invalidation at the same address");
        require(reference.borrow(instance, layout, borrowed) == ENOENT && !borrowed.widget,
                "invalidated selected reference was reacquired by address");
        uintptr_t currentGui = 0, currentRoot = 0;
        require(reference.borrowRoot(instance, layout, currentGui, currentRoot) == 0 &&
                currentGui == target.gui && currentRoot == target.root,
                "selected-widget invalidation discarded the root needed for capture cleanup");
        require(reference.release() == 0 && !reference.owned() && reference.release() == 0,
                "invalidated UI reference did not release all owned storage");
        require(reference.attach(instance, layout, referenceLayout, target) == 0,
                "released UI references could not be registered again");
        require(reference.borrow(instance, layout, borrowed) == 0, "fresh selected reference was lost");
        clearReferences(root.captureTarget);
        require(liveUiTarget(instance, layout, borrowed) == ESTALE &&
                reference.borrow(instance, layout, borrowed) == ESTALE,
                "invalidated root was accepted at the same address");
        require(reference.borrowRoot(instance, layout, currentGui, currentRoot) == ESTALE && !currentGui && !currentRoot,
                "root invalidation published stale cleanup addresses");
        require(reference.release() == 0 && !reference.owned(), "root invalidation lost cleanup ownership");
        require(reference.attach(instance, layout, referenceLayout, target) == 0, "retry fixture registration failed");
        unlinkMode = 1;
        require(reference.release() == EFAULT && reference.owned(), "failed UI reference release lost ownership");
        unlinkMode = 0;
        require(reference.release() == 0 && !reference.owned() && !root.captureTarget.head && !child.captureTarget.head,
                "UI reference release retry did not finish cleanup");
    }
    auto badCapture = captureLayout;
    badCapture.resets[1].offset = badCapture.resets[0].offset;
    require(!validCaptureLayout(badCapture, layout), "overlapping capture reset accepted");
    badCapture = captureLayout;
    badCapture.resets[0].offset = layout.root;
    require(!validCaptureLayout(badCapture, layout), "capture reset may overwrite the UI root");
    badCapture = captureLayout;
    badCapture.widgetTargetable = sizeof(Widget);
    require(!validCaptureLayout(badCapture, layout), "capture targetable exceeds the Widget");
    badCapture = captureLayout;
    badCapture.targeterMember = sizeof(Gui);
    require(!validCaptureLayout(badCapture, layout), "capture targeter exceeds the GUI");
    UiCapture captureState;
    auto beginCapture = [&] {
        require(beginUiCapture(instance, layout, captureLayout, target, captureState) == 0,
                "cannot begin finite capture ownership");
        gui.dragging = 1;
        gui.origin = 123;
    };
    auto finishCapture = [&](uintptr_t already = 0) {
        return finishUiCapture(instance, layout, captureLayout, eventLayout, clockLayout, captureUp, request,
            17, -4, already, captureState);
    };
    beginCapture();
    const auto noCaptureCalls = captureUpCalls;
    require(finishCapture() == 0 && captureState.finished && captureUpCalls == noCaptureCalls &&
            gui.dragging == 1 && gui.origin == 123, "capture-free cleanup changed native state");
    capture(gui.capture, child.captureTarget);
    beginCapture();
    require(finishCapture() == 0 && captureState.finished && gui.capture.target == &child.captureTarget &&
            captureUpCalls == noCaptureCalls && gui.dragging == 1, "preexisting capture was released");
    unlinkCapture(&gui.capture, nullptr);
    other.flags = 8;
    other.parent = &root;
    other.bounds = {200, -300, 50, 50};
    for (unsigned mode = 0; mode <= 4; ++mode) {
        beginCapture();
        capture(gui.capture, other.captureTarget);
        captureRecipient = &child;
        captureUpMode = mode;
        const auto calls = captureUpCalls;
        const int result = finishCapture();
        require(result == (mode == 2 ? EIO : 0) && captureState.finished && captureUpCalls == calls + 1 &&
                captureState.up.entered && captureState.up.returned == (mode != 2),
                "capture up progress, cleanup or original exception was lost");
        require(received.source == &other && received.x == -171 && received.y == 289 && received.time == input.time,
                "transferred capture did not receive original absolute point in its own coordinates");
        if (mode != 3) {
            require(!gui.capture.target && !other.captureTarget.head && !child.captureTarget.head &&
                    !gui.dragging && gui.origin == 0x7fffffff7fffffffULL,
                    "finite capture left target links or native GUI bookkeeping active");
        } else {
            require(gui.capture.target == &other.captureTarget && gui.dragging == 1 && gui.origin == 123,
                    "cleanup accessed bookkeeping after root replacement");
            unlinkCapture(&gui.capture, nullptr);
            gui.root = &root;
        }
        require(finishCapture() == (mode == 2 ? EIO : 0) && captureUpCalls == calls + 1,
                "completed capture cleanup replayed up or lost its terminal failure");
        other.flags &= ~uint8_t{1};
    }
    captureUpMode = 0;
    beginCapture();
    capture(gui.capture, other.captureTarget);
    const auto alreadyCalls = captureUpCalls;
    require(finishCapture(reinterpret_cast<uintptr_t>(&other)) == 0 && !gui.capture.target &&
            captureUpCalls == alreadyCalls && !gui.dragging, "already released widget received a duplicate up");
    capture(gui.capture, child.captureTarget);
    beginCapture();
    capture(gui.capture, other.captureTarget);
    captureUpMode = 1;
    require(finishCapture() == 0 && gui.capture.target == &child.captureTarget && gui.dragging == 1 &&
            gui.origin == 123, "up restored initial capture but cleanup released it");
    unlinkCapture(&gui.capture, nullptr);
    captureUpMode = 0;
    beginCapture();
    capture(gui.capture, other.captureTarget);
    unlinkMode = 1;
    require(finishCapture() == EFAULT && !captureState.finished && captureState.up.entered &&
            gui.capture.target == &other.captureTarget && gui.dragging == 1,
            "failed unlink discarded ownership or reset active capture bookkeeping");
    const auto failedUpCalls = captureUpCalls;
    unlinkMode = 0;
    require(finishCapture() == EFAULT && captureState.finished && !gui.capture.target && !gui.dragging &&
            captureUpCalls == failedUpCalls, "retry lost cleanup or replayed an admitted up");
    beginCapture();
    capture(gui.capture, other.captureTarget);
    captureUpMode = 2;
    unlinkMode = 1;
    require(finishCapture() == EIO && !captureState.finished && captureState.failure == EIO,
            "unlink failure replaced the earlier callback exception");
    const auto combinedFailureCalls = captureUpCalls;
    unlinkMode = 0;
    captureUpMode = 0;
    require(finishCapture() == EIO && captureState.finished && !gui.capture.target && !gui.dragging &&
            captureUpCalls == combinedFailureCalls, "cleanup retry lost the original callback failure");
    beginCapture();
    capture(gui.capture, other.captureTarget);
    first[0] = &other; // Original gesture widget can disappear before a different captured recipient is released.
    require(finishCapture() == 0 && !gui.capture.target && !gui.dragging,
            "capture cleanup depended on the original widget remaining alive");
    first[0] = &child;
    beginCapture();
    Widget removed;
    capture(gui.capture, removed.captureTarget);
    const auto removedCalls = captureUpCalls;
    require(finishCapture() == 0 && !gui.capture.target && !removed.captureTarget.head &&
            captureUpCalls == removedCalls, "removed capture recipient was dispatched or left linked");
    beginCapture();
    capture(gui.capture, other.captureTarget);
    other.parent = &other;
    require(finishCapture() == ELOOP && !captureState.finished && gui.capture.target && !captureState.up.entered,
            "malformed geometry dropped a pending capture up or entered a native parent loop");
    other.parent = nullptr;
    require(finishCapture() == ELOOP && captureState.finished && !gui.capture.target && !gui.dragging,
            "capture up could not resume after its pre-entry geometry failure");
    other.bounds = {};
    resetPageSize = sysconf(_SC_PAGESIZE);
    void *resetMapping = mmap(nullptr, resetPageSize * 2, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    require(resetMapping != MAP_FAILED, "cannot allocate partial reset fixture");
    resetPage = static_cast<unsigned char *>(resetMapping) + resetPageSize;
    auto *mappedGui = reinterpret_cast<Gui *>(static_cast<unsigned char *>(resetPage) - offsetof(Gui, origin));
    std::memcpy(mappedGui, &gui, sizeof(Gui));
    activeGui = mappedGui;
    UiTarget mappedTarget = target;
    mappedTarget.gui = reinterpret_cast<uintptr_t>(mappedGui);
    require(beginUiCapture(instance, layout, captureLayout, mappedTarget, captureState) == 0,
            "cannot begin mapped capture fixture");
    mappedGui->dragging = 1;
    mappedGui->origin = 123;
    capture(mappedGui->capture, other.captureTarget);
    unlinkMode = 2;
    require(finishCapture() == EFAULT && !captureState.finished && captureState.resetWritten == 1 &&
            !mappedGui->capture.target && !mappedGui->dragging && mappedGui->origin == 123,
            "partial reset failure discarded its unfinished bookkeeping");
    const auto partialUpCalls = captureUpCalls;
    require(mprotect(resetPage, resetPageSize, PROT_READ | PROT_WRITE) == 0, "cannot restore reset fixture page");
    mappedGui->dragging = 7;
    unlinkMode = 0;
    require(finishCapture() == EFAULT && captureState.finished && captureState.resetWritten == 2 &&
            mappedGui->dragging == 7 && mappedGui->origin == 0x7fffffff7fffffffULL &&
            captureUpCalls == partialUpCalls, "partial reset retry replayed completed cleanup or native up");
    activeGui = &gui;
    require(munmap(resetMapping, resetPageSize * 2) == 0, "cannot release reset fixture mapping");
    auto enterEventLayout = eventLayout;
    enterEventLayout.hasPrevious = 1;
    enterEventLayout.previous = offsetof(MouseEvent, extra);
    FmLinuxMouseGestureLayout gestureLayout{{offset(&root, &root.flags), 1, 16, 4}, 101, 102, 103, 104, 105,
        offsetof(Gui, previous), offset(&root, &root.captureTarget)};
    UiGestureContext gestureContext{instance, layout, enterEventLayout, clockLayout, captureLayout, gestureLayout,
        {gestureEnter, gestureDown, gestureClick, gestureUp, gestureLeave}};
    checkGestures(gestureContext, target, request, root, child, other);
    checkClicks(gestureContext, modalLayout, selector, *output, root, child, other);
    checkText(gestureContext, modalLayout, selector, *output, root, child);
    checkKeys(gestureContext, modalLayout, selector, *output, root, child);
    auto ambiguous = selector;
    ambiguous.path[0].type[0] = 0;
    ambiguous.path[0].hasText = 0;
    ambiguous.path[0].hasVisible = 0;
    UiTarget rejected = target;
    require(selectUiTarget(&gui, layout, ambiguous, &cancel, *output, rejected) == ENOTUNIQ && !rejected.widget,
            "ambiguous action retained a target");
    selector.path[0].text[0] = 'z';
    require(selectUiTarget(&gui, layout, selector, &cancel, *output, rejected) == ENOENT && !rejected.widget,
            "missing action target retained an old pointer");
    selector.path[0].text[0] = 'l';
    cancel = 1;
    require(selectUiTarget(&gui, layout, selector, &cancel, *output, rejected) == ECANCELED && !rejected.widget &&
            liveUiTarget(instance, layout, target) == 0, "cancellation bypassed required lifetime cleanup checks");
    cancel = 0;
    Widget *cycle[] = {&other};
    other.first = cycle;
    other.last = cycle + 1;
    require(liveUiTarget(instance, layout, target) == ELOOP,
            "liveness stopped at the target without validating the complete traversal");
    other.first = nullptr;
    other.last = nullptr;
    std::array<Widget *, 256> descendants{};
    for (unsigned index = 0; index < descendants.size(); ++index) {
        descendants[index] = &(*ancestors)[index];
        Widget *owner = index == 0 ? &child : &(*ancestors)[index - 1];
        owner->first = &descendants[index];
        owner->last = owner->first + 1;
    }
    require(liveUiTarget(instance, layout, target) == E2BIG, "liveness ignored its traversal depth bound");
    require(selectUiTarget(&gui, layout, selector, &cancel, *output, rejected) == EOVERFLOW && !rejected.widget,
            "action accepted an incomplete traversal");
    child.first = nullptr;
    child.last = nullptr;
    root.flags = 0;
    const char embedded[] = {'x', 0, 'y'};
    child.label = {0, embedded, sizeof(embedded)};
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && output->nodes[1].textSize == sizeof(embedded) &&
            std::memcmp(output->nodes[1].text, embedded, sizeof(embedded)) == 0, "embedded NUL truncated native text");
    std::array<char, FM_LINUX_TEXT_SIZE + 1> longText;
    longText.fill('a');
    child.label = {0, longText.data(), longText.size()};
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && output->truncated &&
            output->nodes[1].textTruncated && output->nodes[1].textTotal == longText.size(), "text bound was not reported");
    rectangleCalls = 0;
    require(snapshotUi(&gui, layout, 10, &cancel, *output, &selector) == 0 && !output->treeTruncated &&
            !output->truncated && !output->nodes[1].selected && !rectangleCalls,
            "unmatched truncated text was treated as an incomplete tree or observed geometry");
    selector.path[0].hasText = 0;
    require(snapshotUi(&gui, layout, 10, &cancel, *output, &selector) == 0 && output->nodes[1].selected &&
            output->truncated && !output->treeTruncated, "selected text truncation was lost");
    child.label = {};
    Unknown unknown;
    first[0] = &unknown;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && !output->nodes[1].textAvailable,
            "unknown override used the base text field");
    first[0] = &child;
    require(snapshotUi(&gui, layout, 1, &cancel, *output) == 0 && output->count == 1 && output->truncated &&
            output->nodes[0].truncated, "node budget was not reported");
    gui.root = &child;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == 0 && output->count == 1 &&
            std::strcmp(output->nodes[0].type, "Label") == 0, "root was retained across snapshots");
    gui.root = &root;
    first[0] = &root;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == ELOOP, "cycle was not rejected");
    first[0] = &child;
    cancel = 1;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == ECANCELED, "cancellation was ignored");
    cancel = 0;
    const size_t pageSize = sysconf(_SC_PAGESIZE);
    void *guard = mmap(nullptr, pageSize, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    require(guard != MAP_FAILED, "cannot allocate guard page");
    root.parent = static_cast<Widget *>(guard);
    rectangleCalls = 0;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EFAULT && !rectangleCalls,
            "unreadable parent entered the native getter");
    root.parent = nullptr;
    child.label = {0, static_cast<const char *>(guard), 1};
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EFAULT, "inaccessible text was not rejected");
    child.label = {};
    first[0] = static_cast<Widget *>(guard);
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EFAULT, "inaccessible widget was not rejected");
    first[0] = &child;
    require(munmap(guard, pageSize) == 0, "cannot release guard page");
    first[0] = reinterpret_cast<Widget *>(UINTPTR_MAX - layout.enabled.offset + 1);
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EFAULT, "wrapping widget pointer was accepted");
    first[0] = &child;
    require(snapshotUi(reinterpret_cast<void *>(UINTPTR_MAX - layout.root + 1), layout, 10, &cancel, *output) == EFAULT,
            "wrapping GUI pointer was accepted");
    const auto savedEnd = root.last;
    root.last = reinterpret_cast<Widget **>(reinterpret_cast<uintptr_t>(root.first) + 1);
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EFAULT, "unaligned child range was accepted");
    root.last = savedEnd;
    layout.enabled.offset = layout.widgetSize;
    require(snapshotUi(&gui, layout, 10, &cancel, *output) == EINVAL, "out-of-bounds layout was accepted");
    std::puts("factorio-mcp UI fixture: passed");
}
