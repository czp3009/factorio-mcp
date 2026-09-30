#include "ui_text.h"
#include "linux_ipc.h"
#include <cerrno>
#include <cstddef>
#include <cstring>

static bool validEvent(const FmLinuxKeyEventLayout &event) {
    if (!event.extent || event.extent > FM_LINUX_KEY_EVENT_BYTES || event.none == event.selectAll ||
        event.none == event.backspace || event.selectAll == event.backspace)
        return false;
    const uint32_t offsets[] = {event.key, event.extended, event.character, event.control, event.source, event.time};
    const uint32_t widths[] = {4, 4, 4, 1, 8, 8};
    for (unsigned index = 0; index < 6; ++index) {
        if (widths[index] > event.extent || offsets[index] > event.extent - widths[index])
            return false;
        for (unsigned previous = 0; previous < index; ++previous)
            if (offsets[index] < offsets[previous] + widths[previous] &&
                offsets[previous] < offsets[index] + widths[index])
                return false;
    }
    return true;
}

int UiTextCommand::record(int error) {
    if (error && !failure_)
        failure_ = error;
    return failure_;
}

int UiTextCommand::borrow(UiTarget &target, void *&textBox) const {
    textBox = nullptr;
    if (const int error = target_.borrow(guiInstance_, ui_, target))
        return error;
    using Cast = void *(*)(const void *, const void *, const void *, ptrdiff_t);
    textBox = reinterpret_cast<Cast>(config_.dynamicCast)(reinterpret_cast<void *>(target.widget),
        reinterpret_cast<void *>(config_.widgetType), reinterpret_cast<void *>(config_.textBoxType), -1);
    return textBox ? 0 : EINVAL;
}

int UiTextCommand::dispatch(uint32_t key, uint32_t character, bool control, const uint32_t *cancel) {
    if (cancel && fm_ipc_load(cancel))
        return ECANCELED;
    UiTarget target;
    void *textBox;
    if (const int error = borrow(target, textBox))
        return error;
    double now;
    if (const int error = readInputClock(reinterpret_cast<void *>(target.gui), config_.clock, now))
        return error;
    if (const int error = borrow(target, textBox))
        return error;
    const auto &layout = config_.event;
    alignas(16) unsigned char event[FM_LINUX_KEY_EVENT_BYTES];
    std::memcpy(event, layout.defaults, sizeof(event));
    const uint8_t modifier = control ? 1 : 0;
    std::memcpy(event + layout.key, &key, sizeof(key));
    std::memcpy(event + layout.extended, &layout.extendedNone, sizeof(layout.extendedNone));
    std::memcpy(event + layout.character, &character, sizeof(character));
    std::memcpy(event + layout.control, &modifier, sizeof(modifier));
    std::memcpy(event + layout.source, &target.widget, sizeof(target.widget));
    std::memcpy(event + layout.time, &now, sizeof(now));
    // The handler's return value is deliberately unused; completion records dispatch, not editing effects.
    using KeyDown = bool (*)(void *, const void *);
    reinterpret_cast<KeyDown>(config_.keyDown)(textBox, event);
    return 0;
}

int UiTextCommand::start(uintptr_t guiInstance, void *gui, const FmLinuxUiLayout &ui,
                         const FmLinuxUiTextConfig &config, const FmLinuxUiSelector &selector,
                         const FmLinuxUiTextRequest &request, const uint32_t *cancel, FmLinuxUiSnapshot &scratch) {
    if (started_)
        return EALREADY;
    started_ = true;
    guiInstance_ = guiInstance;
    ui_ = ui;
    config_ = config;
    request_ = request;
    if (!validEvent(config_.event) || !validInputClockLayout(config_.clock) || config_.clock.guiSize != ui_.guiSize ||
        !validCaptureLayout(config_.capture, ui_) || !validModalLayout(config_.modal, ui_) ||
        config_.capture.widgetTargetable != config_.modal.widgetTargetable ||
        !config_.dynamicCast || !config_.widgetType || !config_.textBoxType || config_.widgetType == config_.textBoxType ||
        !config_.focus || !config_.keyDown || request_.count > FM_LINUX_TEXT_SCALARS) {
        record(EINVAL);
        return finish();
    }
    for (unsigned index = 0; index < request_.count; ++index)
        if (request_.text[index] > 0x10ffff || (request_.text[index] >= 0xd800 && request_.text[index] <= 0xdfff)) {
            record(EINVAL);
            return finish();
        }
    try {
        UiTarget target;
        int error = selectUiActionTarget(guiInstance_, gui, ui_, config_.modal, selector, cancel, scratch, target);
        if (!error && cancel && fm_ipc_load(cancel))
            error = ECANCELED;
        if (!error)
            error = target_.attach(guiInstance_, ui_, config_.capture, target);
        void *textBox;
        if (!error)
            error = borrow(target, textBox);
        if (!error)
            reinterpret_cast<void (*)(void *, bool)>(config_.focus)(reinterpret_cast<void *>(target.widget), false);
        if (!error)
            error = dispatch(config_.event.selectAll, 0, true, cancel);
        if (!error)
            error = dispatch(config_.event.backspace, 0, false, cancel);
        for (unsigned index = 0; !error && index < request_.count; ++index)
            error = dispatch(config_.event.none, request_.text[index], false, cancel);
        record(error);
    } catch (...) {
        record(EFAULT);
    }
    return finish();
}

int UiTextCommand::finish() {
    if (!started_)
        return EINVAL;
    if (finished_)
        return failure_;
    record(target_.release());
    finished_ = !target_.owned();
    return failure_;
}
