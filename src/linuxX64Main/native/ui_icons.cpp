#include "ui_icons.h"
#include "memory_read.h"
#include "ui_stored_text.h"
#include <algorithm>
#include <cerrno>
#include <cxxabi.h>
#include <stdexcept>

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
    if (const auto found = sources.find(sprite); found != sources.end())
        return found->second;
    if (sources.size() >= INT32_MAX)
        throw std::length_error("Icon references exceed integer representation");
    const auto index = static_cast<int32_t>(sources.size());
    sources.emplace(sprite, index);
    return index;
}

int UiIcons::collect(uintptr_t widget, FmLinuxUiNode &node, const FmLinuxIconNamesLayout *namesLayout, UiSnapshot *storage) {
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
    if (!namesLayout || !namesLayout->lookup || !storage)
        return 0;
    const auto &nl = *namesLayout;
    if (!nl.global || !nl.destroy || !nl.stringSize || nl.stringSize > 256 ||
        !fm::member(nl.stringData, 8, nl.stringSize) || !fm::member(nl.stringLength, 8, nl.stringSize))
        return EINVAL;
    uintptr_t global, registry;
    if (!fm::read(nl.global, global) || !fm::addressRange(global, static_cast<size_t>(nl.registry) + 8) ||
        !fm::read(global + nl.registry, registry))
        return EFAULT;
    if (!registry)
        return 0;
    const uintptr_t sprites[] = {normal, hovered, disabled};
    for (size_t i = 0; i < 3; ++i) {
        if (!sprites[i])
            continue;
        auto found = names.find(sprites[i]);
        if (found == names.end()) {
            alignas(16) unsigned char result[256]{};
            reinterpret_cast<void (*)(void *, uintptr_t, uintptr_t)>(nl.lookup)(result, registry, sprites[i]);
            auto destroy = reinterpret_cast<void (*)(void *)>(nl.destroy);
            std::pair<FmLinuxObservationBuffer, bool> copy{};
            bool copied = false;
            try {
                copied = copyStoredText(reinterpret_cast<uintptr_t>(result), nl.stringData, nl.stringLength,
                                        *storage, copy.first, copy.second);
            } catch (...) {
                destroy(result);
                throw;
            }
            destroy(result);
            if (!copied)
                return EFAULT;
            found = names.emplace(sprites[i], copy).first;
        }
        node.iconNames[i] = found->second.first;
        if (found->second.second)
            node.iconNameTruncated |= 1u << i;
    }
    return 0;
}
