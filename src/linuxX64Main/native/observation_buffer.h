#pragma once
#include <stdint.h>

// Owned resident copies, never borrowed game storage. Immutable until the next admitted command.
typedef struct FmLinuxObservationBuffer {
    uint64_t address;
    uint64_t size;
} FmLinuxObservationBuffer;

#ifdef __cplusplus
#include <vector>

template <class T> FmLinuxObservationBuffer observationBuffer(const std::vector<T> &values) {
    return {values.empty() ? 0 : reinterpret_cast<uintptr_t>(values.data()), values.size() * sizeof(T)};
}
#endif
