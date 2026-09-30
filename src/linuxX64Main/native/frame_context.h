#pragma once
#include <stdint.h>

// Addresses and member locations derived from, and checked against, the selected executable.
typedef struct FmLinuxFrameContextConfig {
    uint64_t global;
    uint64_t device;
    uint64_t caller;
    uint64_t getter;
    uint64_t graphicsTable;
    uint64_t windowTable;
    uint32_t globalSize;
    uint32_t globalWindow;
    uint32_t graphicsSize;
    uint32_t graphicsWindow;
    uint32_t windowSize;
    uint32_t windowGraphics;
    uint32_t nativeWindow;
} FmLinuxFrameContextConfig;

typedef struct FmLinuxFrameSize {
    uint32_t width;
    uint32_t height;
} FmLinuxFrameSize;

#ifdef __cplusplus
bool validFrameContext(const FmLinuxFrameContextConfig &config);
// Invoke only from the verified SDL backend swap callback, before forwarding presentation. The callback's
// device/window and return address must be supplied unchanged. Reacquires every object; retains no pointer.
int readFrameSize(const FmLinuxFrameContextConfig &config, uintptr_t device, uintptr_t window,
                  uintptr_t caller, const uint32_t *cancel, FmLinuxFrameSize &output) noexcept;
#endif
