#include "ui_icons.h"
#include "memory_read.h"
#include "native_type.h"
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
    const uintptr_t sprites[] = {normal, hovered, disabled};
    for (size_t i = 0; i < 3; ++i) {
        bool truncated = false;
        if (const int error = name(sprites[i], namesLayout, storage, node.iconNames[i], truncated))
            return error;
        if (truncated)
            node.iconNameTruncated |= 1u << i;
    }
    return 0;
}

int UiIcons::name(uintptr_t sprite, const FmLinuxIconNamesLayout *layout, UiSnapshot *storage,
                  FmLinuxObservationBuffer &output, bool &truncated) {
    output = {};
    truncated = false;
    if (!sprite || !layout || !layout->lookup || !storage)
        return 0;
    if (const auto found = names.find(sprite); found != names.end()) {
        output = found->second.first;
        truncated = found->second.second;
        return 0;
    }
    const auto &nl = *layout;
    if (!nl.global || !nl.destroy || !nl.stringSize || nl.stringSize > 256 ||
        !fm::member(nl.stringData, 8, nl.stringSize) || !fm::member(nl.stringLength, 8, nl.stringSize))
        return EINVAL;
    uintptr_t global, registry;
    if (!fm::read(nl.global, global) || !fm::addressRange(global, static_cast<size_t>(nl.registry) + 8) ||
        !fm::read(global + nl.registry, registry))
        return EFAULT;
    if (!registry)
        return 0;
    alignas(16) unsigned char result[256]{};
    reinterpret_cast<void (*)(void *, uintptr_t, uintptr_t)>(nl.lookup)(result, registry, sprite);
    auto destroy = reinterpret_cast<void (*)(void *)>(nl.destroy);
    bool copied = false;
    try {
        copied = copyStoredText(reinterpret_cast<uintptr_t>(result), nl.stringData, nl.stringLength,
                                *storage, output, truncated);
    } catch (...) {
        destroy(result);
        throw;
    }
    destroy(result);
    if (!copied)
        return EFAULT;
    names.emplace(sprite, std::make_pair(output, truncated));
    return 0;
}

int UiIcons::collectImage(uintptr_t widget, uintptr_t widgetType, const FmUiImageLayout &images, FmLinuxUiNode &node,
                          const FmLinuxIconNamesLayout &names, UiSnapshot &storage) {
    if (!validUiImageLayout(images))
        return EINVAL;
    if (!images.supported)
        return 0;
    if (!widgetType || !fm::addressRange(widget, 8))
        return EFAULT;
    auto cast = [](uintptr_t object, uintptr_t source, uintptr_t target) {
        return reinterpret_cast<uintptr_t>(abi::__dynamic_cast(reinterpret_cast<const void *>(object),
            reinterpret_cast<const abi::__class_type_info *>(source),
            reinterpret_cast<const abi::__class_type_info *>(target), -1));
    };
    const auto receiver = cast(widget, widgetType, images.type);
    if (!receiver)
        return 0;
    if (!fm::addressRange(receiver, images.widgetSize))
        return EFAULT;
    auto &output = node.image;
    output.status = 1;
    uintptr_t image;
    if (!fm::read(receiver + images.image, image))
        return EFAULT;
    if (!image)
        return 0;
    output.status = 2;
    FmLinuxObservationBuffer type{};
    uint32_t size = 0;
    bool truncated = false;
    if (const int error = fm::readTypeName(image, type, size, truncated, storage.strings))
        return error;
    output.type = {type.address, type.size};
    if (const auto plain = cast(image, images.imageType, images.staticType)) {
        // Observe the stored static sprite, never an animation's selected frame.
        uintptr_t sprite;
        if (!fm::addressRange(plain, images.staticSize) || !fm::read(plain + images.sprite, sprite))
            return EFAULT;
        output.spriteObserved = 1;
        output.reference = observe(sprite);
        FmLinuxObservationBuffer copied{};
        if (const int error = name(sprite, &names, &storage, copied, truncated))
            return error;
        output.name = {copied.address, copied.size};
        output.nameTruncated = truncated;
    }
    return 0;
}
