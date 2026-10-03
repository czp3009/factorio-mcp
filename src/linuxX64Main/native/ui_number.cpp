#include "ui_number.h"
#include "memory_read.h"
#include <algorithm>
#include <array>
#include <cerrno>
#include <cxxabi.h>

bool validNumberLayout(const FmLinuxNumberLayout &layout) {
    if (!layout.type)
        return !layout.widgetType && !layout.count && !layout.draw && !layout.zero && !layout.unknown &&
               !layout.infinite;
    if (!layout.widgetType || layout.widgetType == layout.type || layout.type % 8 || layout.widgetType % 8)
        return false;
    std::array<uint32_t, 5> slots{layout.count, layout.draw, layout.zero, layout.unknown, layout.infinite};
    std::sort(slots.begin(), slots.end());
    return slots.back() < 256 && std::adjacent_find(slots.begin(), slots.end()) == slots.end();
}

int collectNumber(uintptr_t widget, const FmLinuxNumberLayout &layout, FmLinuxUiNode &node) {
    if (!validNumberLayout(layout))
        return EINVAL;
    if (!layout.type)
        return 0;
    uintptr_t sourceTable;
    if (!fm::read(widget, sourceTable) || sourceTable < 16)
        return EFAULT;
    // Widget traversal establishes the source object; the selected RTTI establishes both SDK types.
    // The Itanium ABI adjusts cross-casts, including secondary and virtual bases. No concrete widget
    // list or remembered base displacement is used, and the adjusted receiver stays in this safe point.
    try {
        auto *receiver = abi::__dynamic_cast(reinterpret_cast<const void *>(widget),
                                             reinterpret_cast<const abi::__class_type_info *>(layout.widgetType),
                                             reinterpret_cast<const abi::__class_type_info *>(layout.type), -1);
        if (!receiver)
            return 0;
        uintptr_t table;
        if (!fm::read(reinterpret_cast<uintptr_t>(receiver), table))
            return EFAULT;
        auto function = [&](uint32_t slot, uintptr_t &entry) {
            const auto offset = uintptr_t{slot} * sizeof(uintptr_t);
            return fm::addressRange(table, offset + sizeof(uintptr_t)) && fm::read(table + offset, entry) && entry;
        };
        auto flag = [&](uint32_t slot, bool &value) {
            uintptr_t entry;
            if (!function(slot, entry))
                return false;
            value = reinterpret_cast<bool (*)(const void *)>(entry)(receiver);
            return true;
        };
        bool draw, zero, unknown, infinite;
        if (!flag(layout.draw, draw))
            return EFAULT;
        node.numberAvailable = 1;
        if (!draw)
            return 0;
        if (!flag(layout.zero, zero) || !flag(layout.unknown, unknown) || !flag(layout.infinite, infinite))
            return EFAULT;
        node.numberFlags = 1u | (zero ? 2u : 0u) | (unknown ? 4u : 0u) | (infinite ? 8u : 0u);
        if (!unknown && !infinite) {
            uintptr_t entry;
            if (!function(layout.count, entry))
                return EFAULT;
            node.numberValue = reinterpret_cast<double (*)(const void *)>(entry)(receiver);
            node.numberFlags |= 16u;
        }
        return 0;
    } catch (...) {
        return EFAULT;
    }
}
