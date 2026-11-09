#pragma once
#include <stdint.h>

// Owned resident copies, never borrowed game storage. Immutable until the next admitted command.
typedef struct FmLinuxObservationBuffer {
    uint64_t address;
    uint64_t size;
} FmLinuxObservationBuffer;

#ifdef __cplusplus
#include <string_view>
#include <vector>

using ObservationStrings = std::vector<std::vector<char>>;

inline std::string_view observationText(const FmLinuxObservationBuffer &value) {
    return value.size ? std::string_view(reinterpret_cast<const char *>(value.address), value.size)
                      : std::string_view{};
}

template <class T> FmLinuxObservationBuffer observationBuffer(const std::vector<T> &values) {
    return {values.empty() ? 0 : reinterpret_cast<uintptr_t>(values.data()), values.size() * sizeof(T)};
}
#endif
