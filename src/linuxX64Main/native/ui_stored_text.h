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
    if (length > INT32_MAX)
        return false;
    auto &copy = snapshot.strings.emplace_back(length);
    if (!copy.empty() && !fm::readBytes(data, copy.data(), copy.size()))
        return false;
    output = observationBuffer(copy);
    truncated = false;
    return true;
}
