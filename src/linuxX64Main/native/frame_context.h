#pragma once
#include <stdint.h>

// Addresses and member locations derived from, and checked against, the selected executable.
typedef struct FmLinuxFrameContextConfig {
    uint64_t global;
    uint64_t caller;
    uint64_t pixels;
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
// Invoke only from the verified SDL presentation callback. Its window and return address must be
// supplied unchanged. Reacquires every object; retains no pointer.
int readFrameSize(const FmLinuxFrameContextConfig &config, uintptr_t window,
                  uintptr_t caller, const uint32_t *cancel, FmLinuxFrameSize &output) noexcept;
#endif
