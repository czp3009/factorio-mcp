#pragma once
#include "ui_snapshot.h"
#include "memory_read.h"
#include <algorithm>

inline bool copyStoredText(uintptr_t object, uint32_t dataOffset, uint32_t lengthOffset,
                           UiSnapshot &snapshot, FmLinuxObservationBuffer &output, bool &truncated) {
    uintptr_t data;
    uint64_t length;
    if (!fm::read(object + dataOffset, data) || !fm::read(object + lengthOffset, length) || (!data && length))
        return false;
    auto count = std::min<uint64_t>(length, FM_LINUX_TEXT_SIZE);
    auto &copy = snapshot.strings.emplace_back(count + (count < length ? 1 : 0));
    if (!copy.empty() && !fm::readBytes(data, copy.data(), copy.size()))
        return false;
    if (count < length)
        while (count && (static_cast<unsigned char>(copy[count]) & 0xc0) == 0x80)
            --count;
    copy.resize(count);
    output = observationBuffer(copy);
    truncated = count < length;
    return true;
}
