#include "ui_quality.h"
#include "memory_read.h"
#include <cerrno>
#include <cstring>
#include <cxxabi.h>

bool validQualityLayout(const FmLinuxQualityLayout &layout) {
    if (!layout.providerType)
        return !layout.widgetType && !layout.registry && !layout.getter && !layout.width && !layout.quality &&
               !layout.comparison && !layout.registrySize && !layout.first && !layout.last;
    return layout.widgetType && layout.widgetType != layout.providerType && layout.widgetType % 8 == 0 &&
           layout.providerType % 8 == 0 && layout.registry && layout.registry % 8 == 0 && layout.getter < 256 &&
           (layout.width == 1 || layout.width == 2 || layout.width == 4 || layout.width == 8) &&
           layout.quality < layout.width && layout.comparison < layout.width && layout.quality != layout.comparison &&
           layout.registrySize <= 4096 && fm::member(layout.first, 8, layout.registrySize) &&
           fm::member(layout.last, 8, layout.registrySize) && layout.first % 8 == 0 && layout.last % 8 == 0 &&
           layout.first != layout.last;
}

int collectQuality(uintptr_t widget, const FmLinuxQualityLayout &layout, const FmLinuxIdentityLayout &identity,
                   FmLinuxUiQuality &output) {
    output = {};
    if (!validQualityLayout(layout))
        return EINVAL;
    if (!layout.providerType)
        return 0;
    if (!validIdentityLayout(identity) || !identity.providerType || identity.widgetType != layout.widgetType)
        return EINVAL;
    uintptr_t sourceTable;
    if (!fm::read(widget, sourceTable) || sourceTable < 16)
        return EFAULT;
    try {
        const auto *receiver = abi::__dynamic_cast(
            reinterpret_cast<const void *>(widget), reinterpret_cast<const abi::__class_type_info *>(layout.widgetType),
            reinterpret_cast<const abi::__class_type_info *>(layout.providerType), -1);
        if (!receiver)
            return 0;
        uintptr_t table, entry;
        if (!fm::read(reinterpret_cast<uintptr_t>(receiver), table) ||
            !fm::addressRange(table, (layout.getter + 1) * sizeof(uintptr_t)) ||
            !fm::read(table + layout.getter * sizeof(uintptr_t), entry) || !entry)
            return EFAULT;
        uint64_t value;
        switch (layout.width) {
        case 1:
            value = reinterpret_cast<uint8_t (*)(const void *)>(entry)(receiver);
            break;
        case 2:
            value = reinterpret_cast<uint16_t (*)(const void *)>(entry)(receiver);
            break;
        case 4:
            value = reinterpret_cast<uint32_t (*)(const void *)>(entry)(receiver);
            break;
        case 8:
            value = reinterpret_cast<uint64_t (*)(const void *)>(entry)(receiver);
            break;
        default:
            return EINVAL;
        }
        output.available = 1;
        output.quality = (value >> (layout.quality * 8)) & 255;
        output.comparison = (value >> (layout.comparison * 8)) & 255;
        uintptr_t first, last;
        if (!fm::addressRange(layout.registry, layout.registrySize) ||
            !fm::read(layout.registry + layout.first, first) || !fm::read(layout.registry + layout.last, last))
            return EFAULT;
        if (last < first || (last - first) % 8 || (last - first) / 8 > 65536 || ((!first) != (!last)))
            return EPROTO;
        if (output.quality >= (last - first) / 8) {
            output.lookup = 2;
            return 0;
        }
        uintptr_t prototype;
        if (!fm::read(first + output.quality * 8, prototype))
            return EFAULT;
        if (!prototype)
            return 0;
        FmLinuxPrototypeValue name;
        if (const int error = readPrototypeIdentity(prototype, true, identity, name))
            return error;
        output.lookup = 1;
        output.truncated = (name.flags & 2) != 0;
        output.nameSize = name.nameSize;
        memcpy(output.name, name.name, sizeof(output.name));
        return 0;
    } catch (...) {
        return EFAULT;
    }
}
