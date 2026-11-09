#pragma once
#include "memory_read.h"
#include "observation_buffer.h"
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <cxxabi.h>
#include <memory>
#include <string>

namespace fm {
inline int readTypeName(uintptr_t object, FmLinuxObservationBuffer &output, uint32_t &size, bool &truncated,
                        ObservationStrings &strings) {
    output = {};
    size = 0;
    truncated = false;
    uintptr_t table, type, name;
    // Primary Itanium ABI table/RTTI fields, not game member locations.
    if (!read(object, table) || table < sizeof(uintptr_t) || !read(table - sizeof(uintptr_t), type) || !type ||
        type > INTPTR_MAX - sizeof(uintptr_t) || !read(type + sizeof(uintptr_t), name) || !name)
        return EFAULT;
    std::string encoded;
    for (;;) {
        if (name > INTPTR_MAX - encoded.size())
            return EOVERFLOW;
        char byte;
        if (!read(name + encoded.size(), byte))
            return EFAULT;
        if (!byte)
            break;
        if (encoded.size() == INT32_MAX)
            return EOVERFLOW;
        encoded.push_back(byte);
    }
    int status = -1;
    const std::unique_ptr<char, decltype(&std::free)> demangled(
        abi::__cxa_demangle(encoded.c_str(), nullptr, nullptr, &status), &std::free);
    const char *text = status == 0 && demangled ? demangled.get() : encoded.c_str();
    const size_t length = std::strlen(text);
    if (length > INT32_MAX)
        return EOVERFLOW;
    output = observationBuffer(strings.emplace_back(text, text + length));
    size = static_cast<uint32_t>(length);
    return 0;
}
} // namespace fm
