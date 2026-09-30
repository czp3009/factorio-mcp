#include "ui_key.h"
#include "linux_ipc.h"
#include <cerrno>
#include <cmath>

int UiKeyCommand::record(int error) {
    if (error && !failure_)
        failure_ = error;
    return failure_;
}

void UiKeyCommand::cancel() {
    if (gesture_.active()) {
        gesture_.cancel();
        record(ECANCELED);
    }
}

int UiKeyCommand::start(uintptr_t instance, void *gui, const FmLinuxUiLayout &ui, const FmLinuxUiKeyConfig &config,
                        const FmLinuxUiSelector &selector, const FmLinuxUiKeyRequest &request, const uint32_t *canceled,
                        FmLinuxUiSnapshot &scratch) {
    if (started_)
        return EALREADY;
    started_ = true;
    config_ = config;
    if (!validKeyboardEventLayout(config_.event) || !config_.clock.ticks ||
        !std::isfinite(config_.clock.divisor) || config_.clock.divisor <= 0 || !request.count || request.count > 8)
        return record(EINVAL);
    for (unsigned index = 0; index < request.count; ++index)
        for (unsigned previous = 0; previous < index; ++previous)
            if (request.keys[index] == request.keys[previous])
                return record(EINVAL);
    if (canceled && fm_ipc_load(canceled))
        return record(ECANCELED);
    try {
        if (selector.count) {
            if (!config_.focus || !validCaptureLayout(config_.capture, ui) || !validModalLayout(config_.modal, ui) ||
                config_.capture.widgetTargetable != config_.modal.widgetTargetable)
                return record(EINVAL);
            UiTarget target;
            int error = selectUiActionTarget(instance, gui, ui, config_.modal, selector, canceled, scratch, target);
            if (!error)
                error = target_.attach(instance, ui, config_.capture, target);
            if (!error)
                error = target_.borrow(instance, ui, target);
            if (!error && canceled && fm_ipc_load(canceled))
                error = ECANCELED;
            if (!error) {
                reinterpret_cast<void (*)(void *, bool)>(config_.focus)(reinterpret_cast<void *>(target.widget), false);
                error = target_.borrow(instance, ui, target);
            }
            record(error);
            record(target_.release());
        }
        if (!failure_ && canceled && fm_ipc_load(canceled))
            record(ECANCELED);
        if (!failure_)
            gesture_.begin(request.keys, request.count);
    } catch (...) {
        record(EFAULT);
        record(target_.release());
    }
    return failure_;
}

int UiKeyCommand::finish() {
    if (!started_)
        return EINVAL;
    cancel();
    record(target_.release());
    return failure_;
}

int UiKeyCommand::poll(void *event, bool canceled, bool &produced) {
    produced = false;
    if (!gesture_.active())
        return EINVAL;
    if (canceled)
        cancel();
    auto advanced = gesture_;
    uint32_t key;
    bool down;
    if (!advanced.next(key, down)) {
        gesture_ = advanced;
        return 0;
    }
    double now;
    int error = readEventClock(config_.clock, now);
    if (!error)
        error = writeKeyboardEvent(config_.event, event, config_.event.extent, key, down, now);
    if (error) {
        record(error);
        cancel();
        auto cleanup = gesture_;
        if (!cleanup.next(key, down))
            gesture_ = cleanup;
        return error;
    }
    gesture_ = advanced;
    produced = true;
    return 0;
}
