#pragma once
#include "ui_snapshot.h"
#include "memory_read.h"

struct WidgetTextView {
    uintptr_t data = 0;
    uint64_t length = 0;
    bool available = false;
};

// Borrowed only at the frontend safe point. Unknown virtual overrides remain unavailable.
inline bool widgetTextView(uintptr_t object, const FmLinuxTextLayout &layout, WidgetTextView &view) {
    view = {};
    if (!layout.count)
        return true;
    uintptr_t table, function;
    const auto slot = static_cast<uintptr_t>(layout.slot) * sizeof(uintptr_t);
    if (!fm::read(object, table) || table > INTPTR_MAX - slot || !fm::read(table + slot, function))
        return false;
    for (uint32_t index = 0; index < layout.count; ++index) {
        const auto &getter = layout.getters[index];
        if (getter.function != function)
            continue;
        if (!fm::addressRange(object, getter.objectSize))
            return false;
        const auto string = object + getter.offset;
        if (!fm::read(string + layout.data, view.data) || !fm::read(string + layout.length, view.length))
            return false;
        view.available = true;
        return true;
    }
    return true;
}
