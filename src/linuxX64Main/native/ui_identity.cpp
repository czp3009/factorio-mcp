#include "ui_identity.h"
#include "memory_read.h"
#include "native_type.h"
#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <cxxabi.h>

bool validIdentityLayout(const FmLinuxIdentityLayout &layout) {
    if (!layout.providerType)
        return !layout.widgetType && !layout.prototypeSlot && !layout.qualitySlot && !layout.name &&
               !layout.minimumExtent && !layout.qualityBase && !layout.qualitySize && !layout.stringSize &&
               !layout.stringData && !layout.stringLength;
    return layout.widgetType && layout.widgetType != layout.providerType && layout.widgetType % 8 == 0 &&
           layout.providerType % 8 == 0 && layout.prototypeSlot < 256 && layout.qualitySlot < 256 &&
           layout.prototypeSlot != layout.qualitySlot && layout.minimumExtent > 8 && layout.minimumExtent <= 65536 &&
           layout.qualitySize <= 65536 && layout.stringSize > 0 && layout.stringSize <= 256 &&
           fm::member(layout.name, layout.stringSize, layout.minimumExtent) &&
           fm::member(layout.qualityBase, layout.minimumExtent, layout.qualitySize) &&
           fm::member(layout.stringData, 8, layout.stringSize) &&
           fm::member(layout.stringLength, 8, layout.stringSize) &&
           (layout.stringData + 8 <= layout.stringLength || layout.stringLength + 8 <= layout.stringData);
}

int readPrototypeIdentity(uintptr_t object, bool quality, const FmLinuxIdentityLayout &layout,
                          FmLinuxPrototypeValue &output) {
    output = {};
    if (!object)
        return 0;
    if (!validIdentityLayout(layout) || !layout.providerType)
        return EINVAL;
    const auto extent = quality ? layout.qualitySize : layout.minimumExtent;
    if (!fm::addressRange(object, extent))
        return EFAULT;
    output.flags = 1;
    bool typeTruncated;
    if (const int error = fm::readTypeName(object, output.type, output.typeSize, typeTruncated))
        return error;
    if (typeTruncated)
        output.flags |= 4;
    const auto name = object + (quality ? layout.qualityBase : 0) + layout.name;
    uintptr_t data;
    uint64_t length;
    if (!fm::read(name + layout.stringData, data) || !fm::read(name + layout.stringLength, length))
        return EFAULT;
    if (length > 65536)
        return EPROTO;
    if (length && !fm::addressRange(data, length))
        return EFAULT;
    size_t copied = std::min<uint64_t>(length, sizeof(output.name) - 1);
    if (copied && !fm::readBytes(data, output.name, copied))
        return EFAULT;
    if (copied < length) {
        unsigned char next;
        if (!fm::read(data + copied, next))
            return EFAULT;
        while (copied && (next & 0xc0) == 0x80)
            next = static_cast<unsigned char>(output.name[--copied]);
        output.flags |= 2;
    }
    output.name[copied] = 0;
    output.nameSize = copied;
    return 0;
}

int collectIdentity(uintptr_t widget, const FmLinuxIdentityLayout &layout, FmLinuxUiIdentity &output) {
    output = {};
    if (!validIdentityLayout(layout))
        return EINVAL;
    if (!layout.providerType)
        return 0;
    uintptr_t sourceTable;
    if (!fm::read(widget, sourceTable) || sourceTable < 16)
        return EFAULT;
    try {
        const auto *receiver = abi::__dynamic_cast(reinterpret_cast<const void *>(widget),
                                                  reinterpret_cast<const abi::__class_type_info *>(layout.widgetType),
                                                  reinterpret_cast<const abi::__class_type_info *>(layout.providerType), -1);
        if (!receiver)
            return 0;
        uintptr_t table;
        if (!fm::read(reinterpret_cast<uintptr_t>(receiver), table))
            return EFAULT;
        auto read = [&](uint32_t slot, bool quality, FmLinuxPrototypeValue &value) {
            uintptr_t entry;
            if (!fm::addressRange(table, (slot + 1) * sizeof(uintptr_t)) ||
                !fm::read(table + slot * sizeof(uintptr_t), entry) || !entry)
                return EFAULT;
            const auto object = reinterpret_cast<uintptr_t (*)(const void *)>(entry)(receiver);
            return readPrototypeIdentity(object, quality, layout, value);
        };
        if (const int error = read(layout.prototypeSlot, false, output.prototype))
            return error;
        if (const int error = read(layout.qualitySlot, true, output.quality))
            return error;
        output.available = 1;
        return 0;
    } catch (...) {
        return EFAULT;
    }
}
