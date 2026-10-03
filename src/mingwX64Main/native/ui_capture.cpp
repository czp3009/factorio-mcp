#include "ui_capture.h"
#include <cstring>
#include <exception>
#include <utility>

uintptr_t UiCapture::target() const {
    uintptr_t value{};
    memcpy(&value, static_cast<unsigned char *>(gui) + symbols.events.guiCaptureTarget, sizeof(value));
    return value;
}

UiCapture::UiCapture(const Symbols &symbols, void *gui, std::function<bool()> current, std::function<bool(void *)> live)
    : symbols(symbols), gui(gui), current(std::move(current)), live(std::move(live)) {}

void UiCapture::release(const void *absoluteEvent, void *alreadyReleased) {
    if (finished)
        return;
    finished = true;
    if (!current())
        return;
    const auto captured = target();
    if (!captured)
        return;
    std::exception_ptr failure;
    try {
        if (captured >= symbols.events.widgetTargetable) {
            void *widget = reinterpret_cast<void *>(captured - symbols.events.widgetTargetable);
            if (widget != alreadyReleased && live(widget)) {
                alignas(16) unsigned char event[256]{};
                reinterpret_cast<void *(*)(void *, void *, void *, const void *)>(symbols.address[RelativeMouseEvent])(
                    gui, event, widget, absoluteEvent);
                memcpy(event + symbols.events.eventType, &symbols.events.up, sizeof(symbols.events.up));
                reinterpret_cast<void (*)(void *, const void *)>(symbols.address[DispatchUp])(widget, event);
            }
        }
    } catch (...) {
        failure = std::current_exception();
    }
    // The handler may have destroyed the widget or changed the root. Recheck GUI ownership.
    if (current())
        reinterpret_cast<void (*)(void *)>(symbols.address[ReleaseMouseCapture])(gui);
    if (failure)
        std::rethrow_exception(failure);
}
