#pragma once
#include <cstddef>
#include <cstdint>
#include <sys/uio.h>
#include <unistd.h>

namespace fm {
inline bool member(uint32_t offset, uint32_t width, uint32_t size) {
    return width && offset <= size && width <= size - offset;
}

inline bool addressRange(uintptr_t address, size_t size) {
    return address && size && size <= INTPTR_MAX && address <= static_cast<uintptr_t>(INTPTR_MAX) - size;
}

// Kernel-mediated reads reject malformed live pointers instead of faulting the game.
inline bool readBytes(uintptr_t address, void *output, size_t size) {
    if (!addressRange(address, size))
        return false;
    iovec local{output, size};
    iovec remote{reinterpret_cast<void *>(address), size};
    return process_vm_readv(getpid(), &local, 1, &remote, 1, 0) == static_cast<ssize_t>(size);
}

template <class T> bool read(uintptr_t address, T &output) {
    return readBytes(address, &output, sizeof(T));
}
} // namespace fm
