#pragma once
#include <stdint.h>

typedef struct FmLinuxFrameApiEntry {
    uint64_t storage;
    uint64_t function;
} FmLinuxFrameApiEntry;

typedef struct FmLinuxFrameApiConfig {
    FmLinuxFrameApiEntry getInteger;
    FmLinuxFrameApiEntry bindFramebuffer;
    FmLinuxFrameApiEntry bindBuffer;
    FmLinuxFrameApiEntry pixelStore;
    FmLinuxFrameApiEntry readPixels;
    FmLinuxFrameApiEntry getError;
} FmLinuxFrameApiConfig;

#ifdef __cplusplus
#include "frame_readback.h"

// Each expected function must first be established as the corresponding SDK entry in executable memory.
// Revalidates the game's loader slots, without calling GL. Context/thread admission remains a caller obligation.
int readFrameApi(const FmLinuxFrameApiConfig &config, FrameReadbackApi &output);
#endif
