#include "ui_click.h"
#include "linux_ipc.h"
#include "memory_read.h"
#include <cerrno>
#include <cmath>

int UiClickCommand::record(int error) {
    if (error && !failure_)
        failure_ = error;
    return failure_;
}

UiGestureContext UiClickCommand::context() const {
    using Entry = void (*)(void *, const void *);
    const auto &g = config_.gesture;
    return {guiInstance_, ui_, g.event, g.clock, g.capture, g.gesture,
        {reinterpret_cast<Entry>(g.enter), reinterpret_cast<Entry>(g.down), reinterpret_cast<Entry>(g.click),
         reinterpret_cast<Entry>(g.up), reinterpret_cast<Entry>(g.leave)}};
}

InputStateFunctions UiClickCommand::stateFunctions() const {
    using Entry = void (*)(void *, const void *);
    return {reinterpret_cast<Entry>(config_.state.update), reinterpret_cast<Entry>(config_.state.postUpdate)};
}

int UiClickCommand::time(double &output) const {
    uintptr_t gui;
    if (!fm::read(guiInstance_, gui))
        return EFAULT;
    if (!gui)
        return ENOENT;
    return readInputClock(reinterpret_cast<void *>(gui), config_.gesture.clock, output);
}

int UiClickCommand::releaseInput() {
    if (gesture_.started() && !gesture_.finished())
        return failure_;
    if (button_.owned())
        record(button_.reconcile(config_.state.layout));
    if (button_.owned()) {
        double now;
        if (const int error = time(now))
            record(error);
        else
            record(button_.release(config_.state.layout, stateFunctions(), now));
    }
    for (unsigned index = 3; index-- > 0;) {
        auto &key = keys_[index];
        if (key.owned())
            record(key.reconcile(config_.state.layout, config_.keyboard));
        if (key.owned()) {
            double now;
            if (const int error = time(now))
                record(error);
            else
                record(key.release(config_.state.layout, config_.keyboard, stateFunctions(), now));
        }
    }
    finished_ = !button_.owned();
    for (const auto &key : keys_)
        finished_ &= !key.owned();
    return failure_;
}

int UiClickCommand::start(uintptr_t guiInstance, void *gui, const FmLinuxUiLayout &ui,
                          const FmLinuxUiClickConfig &config, const FmLinuxUiSelector &selector,
                          const FmLinuxUiClickRequest &request, const uint32_t *cancel, FmLinuxUiSnapshot &scratch) {
    if (started_)
        return EALREADY;
    started_ = true;
    guiInstance_ = guiInstance;
    ui_ = ui;
    config_ = config;
    const auto ctx = context();
    bool validCodes = true;
    for (unsigned index = 0; index < 3; ++index) {
        bool found = false;
        for (auto code : config_.state.layout.codes)
            found |= config_.codes[index] == code;
        validCodes &= found && config_.gesture.buttons[index] != 0;
        for (unsigned previous = 0; previous < index; ++previous)
            validCodes &= config_.codes[index] != config_.codes[previous] &&
                config_.gesture.buttons[index] != config_.gesture.buttons[previous];
    }
    if (!validMouseGesture(ctx) || !validModalLayout(config_.gesture.modal, ui_) ||
        !validMouseStateLayout(config_.state.layout) || !config_.state.update || !config_.state.postUpdate ||
        !validCodes || request.button >= 3 || !std::isfinite(request.x) || !std::isfinite(request.y) ||
        request.x < 0 || request.x > 1 || request.y < 0 || request.y > 1 ||
        request.control > 1 || request.shift > 1 || request.alt > 1 ||
        ((request.control || request.shift || request.alt) && !validKeyboardStateConfig(config_.state.layout, config_.keyboard))) {
        finished_ = true;
        return record(EINVAL);
    }
    UiTarget target;
    int error = selectUiActionTarget(guiInstance_, gui, ui_, config_.gesture.modal, selector, cancel, scratch, target);
    if (!error && (target.bounds.width <= 0 || target.bounds.height <= 0))
        error = EINVAL;
    double now = 0;
    if (!error)
        error = time(now);
    if (!error)
        error = liveUiTarget(guiInstance_, ui_, target);
    if (!error && cancel && fm_ipc_load(cancel))
        error = ECANCELED;
    const uint32_t modifiers[] = {request.control, request.shift, request.alt};
    for (unsigned index = 0; !error && index < 3; ++index)
        if (modifiers[index])
            error = keys_[index].press(config_.state.layout, config_.keyboard, stateFunctions(),
                config_.keyboard.codes[index], now);
    if (!error)
        error = button_.press(config_.state.layout, stateFunctions(), config_.codes[request.button], now);
    record(error);
    if (!error) {
        UiMouseRequest mouse;
        mouse.button = config_.gesture.buttons[request.button];
        mouse.x = request.x;
        mouse.y = request.y;
        mouse.control = request.control != 0;
        mouse.shift = request.shift != 0;
        mouse.alt = request.alt != 0;
        record(gesture_.start(ctx, target, mouse));
    }
    // Once admitted, cancellation cannot skip either kind of release. No click phase is replayed by finish().
    return releaseInput();
}

int UiClickCommand::finish() {
    if (!started_)
        return EINVAL;
    if (finished_)
        return failure_;
    if (gesture_.started() && !gesture_.finished())
        record(gesture_.finish(context()));
    return releaseInput();
}
