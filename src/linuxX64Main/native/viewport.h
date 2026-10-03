#pragma once
#include "lua_query.h"
#include "player_objects.h"

typedef struct FmLinuxViewportLayout {
    uint64_t framebufferVtable;
    uint64_t framebufferTypeInfo;
    uint64_t width;
    uint64_t height;
    uint64_t mapPosition;
    uint32_t renderer;
    uint32_t rendererSize;
    uint32_t framebufferReference;
    uint32_t framebufferSize;
    uint32_t primary;
    uint32_t fallback;
    uint32_t widthSlot;
    uint32_t heightSlot;
    uint32_t surface;
    uint32_t position;
    uint32_t fractionBits;
} FmLinuxViewportLayout;

#ifdef __cplusplus
// All references are reacquired in the same verified frontend safe point as the world query.
int readViewport(uintptr_t game, uintptr_t player, const FmLinuxPlayerLayout &selection,
                 const FmLinuxViewportLayout &layout, const uint32_t *cancel, QueryViewport &output);
#endif
