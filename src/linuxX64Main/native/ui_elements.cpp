#include "ui_elements.h"
#include "memory_read.h"
#include "native_type.h"
#include <cerrno>
#include <cxxabi.h>

bool validElementLayout(const FmLinuxElementLayout &layout) {
    if (!layout.stackProvider)
        return !layout.widgetType && !layout.itemProvider && !layout.itemType && !layout.toolType &&
               !layout.ammoType && !layout.stackGetter && !layout.itemGetter && !layout.stackExtent &&
               !layout.itemSize && !layout.toolSize && !layout.ammoSize && !layout.stackItem && !layout.count &&
               !layout.health && !layout.durability && !layout.magazine;
    const uint64_t types[] = {layout.widgetType, layout.stackProvider, layout.itemProvider,
                              layout.itemType, layout.toolType, layout.ammoType};
    for (size_t index = 0; index < sizeof(types) / sizeof(*types); ++index) {
        if (!types[index] || types[index] % 8)
            return false;
        for (size_t previous = 0; previous < index; ++previous)
            if (types[index] == types[previous])
                return false;
    }
    return layout.stackGetter < 256 && layout.itemGetter < 256 && layout.stackExtent <= 65536 &&
           layout.itemSize <= 65536 && layout.toolSize <= 65536 && layout.ammoSize <= 65536 &&
           fm::member(layout.stackItem, 8, layout.stackExtent) && fm::member(layout.count, 4, layout.stackExtent) &&
           (layout.stackItem + 8 <= layout.count || layout.count + 4 <= layout.stackItem) &&
           fm::member(layout.health, 4, layout.itemSize) && fm::member(layout.durability, 8, layout.toolSize) &&
           fm::member(layout.magazine, 4, layout.ammoSize);
}

int collectElement(uintptr_t widget, const FmLinuxElementLayout &layout, FmLinuxUiElement &output) {
    output = {};
    if (!validElementLayout(layout))
        return EINVAL;
    if (!layout.stackProvider)
        return 0;
    uintptr_t sourceTable;
    if (!fm::read(widget, sourceTable) || sourceTable < 16)
        return EFAULT;
    try {
        auto cast = [](uintptr_t object, uint64_t source, uint64_t target) {
            return reinterpret_cast<uintptr_t>(abi::__dynamic_cast(reinterpret_cast<const void *>(object),
                reinterpret_cast<const abi::__class_type_info *>(source),
                reinterpret_cast<const abi::__class_type_info *>(target), -1));
        };
        auto get = [](uintptr_t receiver, uint32_t slot, uintptr_t &object) {
            uintptr_t table, entry;
            if (!fm::read(receiver, table) || !fm::addressRange(table, (slot + 1) * sizeof(uintptr_t)) ||
                !fm::read(table + slot * sizeof(uintptr_t), entry) || !entry)
                return EFAULT;
            object = reinterpret_cast<uintptr_t (*)(const void *)>(entry)(reinterpret_cast<const void *>(receiver));
            return 0;
        };
        uintptr_t item = 0;
        if (const auto receiver = cast(widget, layout.widgetType, layout.stackProvider)) {
            output.available = 1;
            output.flags = 1;
            uintptr_t stack;
            if (const int error = get(receiver, layout.stackGetter, stack))
                return error;
            if (stack) {
                if (!fm::addressRange(stack, layout.stackExtent) || !fm::read(stack + layout.count, output.count) ||
                    !fm::read(stack + layout.stackItem, item))
                    return EFAULT;
                output.flags |= 2;
            }
        } else if (const auto receiver = cast(widget, layout.widgetType, layout.itemProvider)) {
            output.available = 1;
            if (const int error = get(receiver, layout.itemGetter, item))
                return error;
            if (item)
                output.flags |= 2;
        }
        if (!item)
            return 0;
        output.flags |= 4;
        if (!fm::addressRange(item, layout.itemSize) || !fm::read(item + layout.health, output.health))
            return EFAULT;
        bool truncated;
        if (const int error = fm::readTypeName(item, output.type, output.typeSize, truncated))
            return error;
        if (truncated)
            output.flags |= 32;
        if (const auto tool = cast(item, layout.itemType, layout.toolType)) {
            if (!fm::addressRange(tool, layout.toolSize) || !fm::read(tool + layout.durability, output.durability))
                return EFAULT;
            output.flags |= 8;
        }
        if (const auto ammo = cast(item, layout.itemType, layout.ammoType)) {
            if (!fm::addressRange(ammo, layout.ammoSize) || !fm::read(ammo + layout.magazine, output.magazine))
                return EFAULT;
            output.flags |= 16;
        }
        return 0;
    } catch (...) {
        return EFAULT;
    }
}
