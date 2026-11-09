#pragma once
#include <stdint.h>

typedef struct FmLinuxToggleLayout {
    uint64_t widgetType, buttonType;
    uint32_t objectSize, offset, modeOffset;
} FmLinuxToggleLayout;

#ifdef __cplusplus
#include "memory_read.h"
#include <cerrno>
#include <cxxabi.h>

inline bool validToggleLayout(const FmLinuxToggleLayout &layout) {
    if (!layout.buttonType)
        return !layout.widgetType && !layout.objectSize && !layout.offset && !layout.modeOffset;
    return layout.widgetType && layout.widgetType != layout.buttonType && layout.widgetType % 8 == 0 &&
           layout.buttonType % 8 == 0 && layout.objectSize <= 16 * 1024 * 1024 &&
           fm::member(layout.offset, 1, layout.objectSize) && fm::member(layout.modeOffset, 1, layout.objectSize) &&
           layout.offset != layout.modeOffset;
}

inline int readToggle(uintptr_t widget, const FmLinuxToggleLayout &layout, uint32_t &available,
                      uint32_t &toggled, uint32_t &toggleMode) {
    available = 0;
    if (!validToggleLayout(layout))
        return EINVAL;
    if (!layout.buttonType)
        return 0;
    uintptr_t table;
    if (!fm::read(widget, table) || table < 16)
        return EFAULT;
    try {
        const auto *receiver = abi::__dynamic_cast(reinterpret_cast<const void *>(widget),
            reinterpret_cast<const abi::__class_type_info *>(layout.widgetType),
            reinterpret_cast<const abi::__class_type_info *>(layout.buttonType), -1);
        if (!receiver)
            return 0;
        const auto address = reinterpret_cast<uintptr_t>(receiver);
        uint8_t value, mode;
        if (!fm::addressRange(address, layout.objectSize) || !fm::read(address + layout.offset, value) ||
            !fm::read(address + layout.modeOffset, mode))
            return EFAULT;
        if (value > 1 || mode > 1)
            return EPROTO;
        available = 1;
        toggled = value;
        toggleMode = mode;
        return 0;
    } catch (...) {
        return EFAULT;
    }
}
#endif
