#include "ui_icons.h"
#include "memory_read.h"
#include <algorithm>
#include <cerrno>
#include <cxxabi.h>

bool validIconLayout(const FmLinuxIconLayout &layout) {
    if (!layout.type)
        return !layout.widgetType && !layout.iconSize && !layout.normal && !layout.hovered && !layout.disabled;
    if (!layout.widgetType || layout.type == layout.widgetType || layout.type % 8 || layout.widgetType % 8 ||
        layout.iconSize < 8 || layout.iconSize > 65536)
        return false;
    std::array<uint32_t, 3> fields{layout.normal, layout.hovered, layout.disabled};
    std::sort(fields.begin(), fields.end());
    return std::adjacent_find(fields.begin(), fields.end()) == fields.end() &&
           std::all_of(fields.begin(), fields.end(), [&](uint32_t offset) {
               return offset >= 8 && offset % 8 == 0 && fm::member(offset, 8, layout.iconSize);
           });
}

UiIcons::UiIcons(const FmLinuxIconLayout &layout) : layout(layout) {}

int32_t UiIcons::observe(uintptr_t sprite) {
    if (!sprite)
        return -1;
    for (uint32_t index = 0; index < count; ++index)
        if (sources[index] == sprite)
            return static_cast<int32_t>(index);
    if (count == sources.size())
        return -2;
    sources[count] = sprite;
    return static_cast<int32_t>(count++);
}

int UiIcons::collect(uintptr_t widget, FmLinuxUiNode &node) {
    if (!validIconLayout(layout))
        return EINVAL;
    if (!layout.type)
        return 0;
    uintptr_t table;
    if (!fm::read(widget, table) || table < 16)
        return EFAULT;
    const auto *receiver = abi::__dynamic_cast(reinterpret_cast<const void *>(widget),
                                               reinterpret_cast<const abi::__class_type_info *>(layout.widgetType),
                                               reinterpret_cast<const abi::__class_type_info *>(layout.type), -1);
    if (!receiver)
        return 0;
    const auto object = reinterpret_cast<uintptr_t>(receiver);
    if (!fm::addressRange(object, layout.iconSize))
        return EFAULT;
    uintptr_t normal, hovered, disabled;
    if (!fm::read(object + layout.normal, normal) || !fm::read(object + layout.hovered, hovered) ||
        !fm::read(object + layout.disabled, disabled))
        return EFAULT;
    node.iconsAvailable = 1;
    node.iconNormal = observe(normal);
    node.iconHovered = observe(hovered);
    node.iconDisabled = observe(disabled);
    return 0;
}
