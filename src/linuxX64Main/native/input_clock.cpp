#include "input_clock.h"
#include "memory_read.h"
#include <cerrno>
#include <cmath>

bool validInputClockLayout(const FmLinuxInputClockLayout &layout) {
    return layout.guiSize <= 16 * 1024 * 1024 && fm::member(layout.member, sizeof(uintptr_t), layout.guiSize) &&
        layout.handlerSize >= sizeof(uintptr_t) && layout.handlerSize <= 16 * 1024 * 1024 && layout.slot <= 4095 &&
        layout.member % alignof(uintptr_t) == 0 && layout.handlerTable % alignof(uintptr_t) == 0 &&
        fm::addressRange(layout.handlerTable, (static_cast<uintptr_t>(layout.slot) + 1) * sizeof(uintptr_t)) &&
        fm::addressRange(layout.function, 1);
}

namespace {
int handler(uintptr_t gui, const FmLinuxInputClockLayout &layout, uintptr_t &output) {
    uintptr_t value, table, function;
    if (!fm::read(gui + layout.member, value))
        return EFAULT;
    if (!value)
        return ENOENT;
    if (value % alignof(uintptr_t) || !fm::addressRange(value, layout.handlerSize) || !fm::read(value, table))
        return EFAULT;
    if (table != layout.handlerTable)
        return EPROTO;
    if (!fm::read(table + static_cast<uintptr_t>(layout.slot) * sizeof(uintptr_t), function))
        return EFAULT;
    if (function != layout.function)
        return EPROTO;
    output = value;
    return 0;
}
} // namespace

int readInputClock(void *gui, const FmLinuxInputClockLayout &layout, double &output) {
    output = 0;
    if (!gui || !validInputClockLayout(layout))
        return EINVAL;
    const auto address = reinterpret_cast<uintptr_t>(gui);
    if (address % alignof(uintptr_t) || !fm::addressRange(address, layout.guiSize))
        return EFAULT;
    uintptr_t before;
    if (const int error = handler(address, layout, before))
        return error;
    double value;
    try {
        using Clock = double (*)(const void *);
        value = reinterpret_cast<Clock>(layout.function)(reinterpret_cast<const void *>(before));
    } catch (...) {
        return EIO;
    }
    uintptr_t after;
    if (const int error = handler(address, layout, after))
        return error;
    if (after != before)
        return ESTALE;
    if (!std::isfinite(value) || value < 0)
        return ERANGE;
    output = value;
    return 0;
}
