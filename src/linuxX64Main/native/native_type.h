#pragma once
#include "memory_read.h"
#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <cxxabi.h>

namespace fm {
template <size_t Capacity>
int readTypeName(uintptr_t object, char (&output)[Capacity], uint32_t &size, bool &truncated) {
    static_assert(Capacity > 1 && Capacity <= 4096);
    uintptr_t table, type, encodedName;
    if (!read(object, table) || table < 8 || !read(table - 8, type) || !addressRange(type, 16) ||
        !read(type + 8, encodedName) || !addressRange(encodedName, Capacity))
        return EFAULT;
    char encoded[Capacity];
    size_t encodedSize = 0;
    for (; encodedSize + 1 < Capacity; ++encodedSize) {
        if (!read(encodedName + encodedSize, encoded[encodedSize]))
            return EFAULT;
        if (!encoded[encodedSize])
            break;
    }
    encoded[encodedSize] = 0;
    truncated = encodedSize + 1 == Capacity;
    int status = -1;
    char *demangled = truncated ? nullptr : abi::__cxa_demangle(encoded, nullptr, nullptr, &status);
    const char *text = status == 0 && demangled ? demangled : encoded;
    const auto length = strnlen(text, Capacity);
    size = std::min(length, Capacity - 1);
    std::memcpy(output, text, size);
    output[size] = 0;
    std::free(demangled);
    truncated |= length >= Capacity;
    return 0;
}
} // namespace fm
