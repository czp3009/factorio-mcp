#include "mouse_event.h"
#include "targeter.h"
#include "memory_read.h"
#include <cerrno>
#include <cmath>
#include <cstring>
#include <utility>

bool validMouseEventLayout(const FmLinuxMouseEventLayout &layout) {
    if (!layout.size || layout.size > FM_LINUX_MOUSE_EVENT_BYTES || layout.hasPrevious > 1 ||
        (!layout.hasPrevious && layout.previous))
        return false;
    const std::pair<uint32_t, uint32_t> fields[] = {
        {layout.x, 4}, {layout.y, 4}, {layout.source, sizeof(uintptr_t)}, {layout.wheel, 4}, {layout.button, 2},
        {layout.type, 4}, {layout.time, 8}, {layout.alt, 1}, {layout.control, 1}, {layout.shift, 1},
        {layout.previous, sizeof(uintptr_t)}};
    const auto count = sizeof(fields) / sizeof(fields[0]) - (layout.hasPrevious ? 0 : 1);
    for (unsigned index = 0; index < count; ++index) {
        const auto [offset, width] = fields[index];
        if (!fm::member(offset, width, layout.size))
            return false;
        for (unsigned previous = 0; previous < index; ++previous) {
            const auto [other, size] = fields[previous];
            if (!(offset + width <= other || other + size <= offset))
                return false;
        }
    }
    return true;
}

int dispatchUiMouse(uintptr_t guiInstance, const FmLinuxUiLayout &ui, const FmLinuxMouseEventLayout &layout,
                    const FmLinuxInputClockLayout &clock,
                    void (*entry)(void *, const void *), const UiTarget &target, const UiMouseRequest &request,
                    UiMouseDispatch &output) {
    output = {};
    if (!entry || !validMouseEventLayout(layout) || !request.button || clock.guiSize != ui.guiSize ||
        !std::isfinite(request.x) || !std::isfinite(request.y) || request.x < 0 || request.x > 1 ||
        request.y < 0 || request.y > 1 || target.bounds.width <= 0 || target.bounds.height <= 0)
        return EINVAL;
    return dispatchUiMouseAt(guiInstance, ui, layout, clock, entry, target, request,
        static_cast<int32_t>((target.bounds.width - 1) * request.x),
        static_cast<int32_t>((target.bounds.height - 1) * request.y), output);
}

int dispatchUiMouseAt(uintptr_t guiInstance, const FmLinuxUiLayout &ui, const FmLinuxMouseEventLayout &layout,
                      const FmLinuxInputClockLayout &clock, void (*entry)(void *, const void *),
                      const UiTarget &target, const UiMouseRequest &request, int32_t x, int32_t y,
                      UiMouseDispatch &output) {
    output = {};
    if (!entry || !validMouseEventLayout(layout) || !request.button || clock.guiSize != ui.guiSize ||
        (request.previous && !layout.hasPrevious))
        return EINVAL;
    output.targetStatus = liveUiTarget(guiInstance, ui, target);
    if (output.targetStatus)
        return output.targetStatus;
    const auto previousStatus = [&] {
        if (request.previousReference) {
            uintptr_t previous;
            if (const int error = request.previousReference->borrow(previous))
                return error;
            if (request.previous ? previous < request.previousTargetable ||
                previous - request.previousTargetable != request.previous : previous != 0)
                return ENOENT;
        }
        return request.previous ? liveUiTarget(guiInstance, ui, {target.gui, target.root, request.previous, {}}) : 0;
    };
    if (const int error = previousStatus())
        return error;
    double time;
    if (const int error = readInputClock(reinterpret_cast<void *>(target.gui), clock, time))
        return error;
    output.targetStatus = liveUiTarget(guiInstance, ui, target);
    if (output.targetStatus)
        return output.targetStatus;
    if (const int error = previousStatus())
        return error;
    alignas(16) unsigned char event[FM_LINUX_MOUSE_EVENT_BYTES]{};
    const auto field = [&]<class T>(uint32_t offset, T value) {
        std::memcpy(event + offset, &value, sizeof(value));
    };
    field(layout.x, x);
    field(layout.y, y);
    field(layout.source, target.widget);
    field(layout.button, request.button);
    field(layout.type, request.type);
    field(layout.time, time);
    field(layout.alt, static_cast<uint8_t>(request.alt));
    field(layout.control, static_cast<uint8_t>(request.control));
    field(layout.shift, static_cast<uint8_t>(request.shift));
    if (layout.hasPrevious)
        field(layout.previous, request.previous);
    int error = 0;
    output.entered = true;
    try {
        entry(reinterpret_cast<void *>(target.widget), event);
        output.returned = true;
    } catch (...) {
        error = EIO;
    }
    output.targetStatus = liveUiTarget(guiInstance, ui, target);
    // Removing the target/root is a normal callback outcome, not a failed gameplay effect or reason to replay.
    if (!error && output.targetStatus != ENOENT && output.targetStatus != ESTALE)
        error = output.targetStatus;
    return error;
}
