#pragma once
#include <stdint.h>

typedef struct FmLinuxPlayerLayout {
    uint64_t playerVtable;
    uint64_t playerTypeInfo;
    uint64_t viewVtable;
    uint64_t viewTypeInfo;
    uint32_t gameSize;
    uint32_t playerSize;
    uint32_t viewSize;
    uint32_t gamePlayer;
    uint32_t gameView;
    uint32_t viewPlayer;
    uint32_t index;
    uint32_t indexWidth;
} FmLinuxPlayerLayout;

#ifdef __cplusplus
struct PlayerObjects {
    uintptr_t player = 0;
    uint32_t index = 0;
};

bool validPlayerLayout(const FmLinuxPlayerLayout &layout);
// Game must be acquired in this same frontend safe point. No pointer survives the callback.
int readLocalPlayer(uintptr_t game, const FmLinuxPlayerLayout &layout, const uint32_t *cancel, PlayerObjects &output);
#endif
