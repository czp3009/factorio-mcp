#pragma once
#include <stdint.h>

typedef struct FmRequestBuffer {
    uint64_t address;
    uint64_t size;
} FmRequestBuffer;

#ifdef __cplusplus
#include <climits>
#include <stdexcept>
#include <string_view>
#include <vector>

namespace fm {
class RequestStorage {
  public:
    FmRequestBuffer reserve(uint32_t size) {
        if (size > INT_MAX)
            throw std::length_error("Request exceeds byte-array representation");
        std::vector<unsigned char> replacement(size);
        bytes_.swap(replacement);
        return {bytes_.empty() ? 0 : reinterpret_cast<uintptr_t>(bytes_.data()), bytes_.size()};
    }

    bool owns(const FmRequestBuffer &value) const {
        if (!value.size)
            return !value.address;
        const auto first = reinterpret_cast<uintptr_t>(bytes_.data());
        return value.address >= first && value.size <= bytes_.size() &&
               value.address - first <= bytes_.size() - value.size;
    }

    void clear() noexcept {
        std::vector<unsigned char>().swap(bytes_);
    }

  private:
    std::vector<unsigned char> bytes_;
};

inline std::string_view requestView(const FmRequestBuffer &value) {
    if (value.size > INT_MAX || (value.size && (value.address < 4096 || value.address > INTPTR_MAX - value.size)))
        throw std::invalid_argument("Invalid request byte range");
    return value.size ? std::string_view(reinterpret_cast<const char *>(value.address), value.size) : std::string_view{};
}
} // namespace fm
#endif
