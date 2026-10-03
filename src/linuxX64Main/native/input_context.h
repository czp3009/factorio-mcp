#pragma once
#include "world_objects.h"

#define FM_LINUX_INPUT_HANDLER_CANDIDATES 64

typedef struct FmLinuxInputContextLayout {
    uint64_t sourceVtable;
    uint64_t sourceTypeInfo;
    uint64_t playerVtable;
    uint64_t playerTypeInfo;
    uint64_t viewVtable;
    uint64_t viewTypeInfo;
    uint64_t handlerVtable;
    uint64_t handlerTypeInfo;
    uint32_t sourceSize;
    uint32_t playerSize;
    uint32_t mapSize;
    uint32_t viewSize;
    uint32_t handlerSize;
    uint32_t globalSource;
    uint32_t sourcePlayer;
    uint32_t playerMap;
    uint32_t mapGame;
    uint32_t gameView;
    uint32_t gameSource;
    uint32_t viewPlayer;
    uint32_t scriptMap;
    uint32_t mapTick;
    uint32_t mapStop;
    uint32_t mapPaused;
    uint32_t handlerMap;
    uint32_t handlerSource;
    uint32_t handlerCount;
    uint32_t handlers[FM_LINUX_INPUT_HANDLER_CANDIDATES];
    uint64_t forwardedVtable;
    uint64_t forwardedTypeInfo;
    uint32_t forwardedSize;
    uint32_t forwardedSource;
} FmLinuxInputContextLayout;

typedef struct FmLinuxInputContextConfig {
    FmLinuxWorldLayout world;
    FmLinuxScriptLayout script;
    FmLinuxInputContextLayout input;
} FmLinuxInputContextConfig;

#ifdef __cplusplus
// Callback-local references, not a lifetime token. No pointer may survive dispatch, a frame or a world change.
struct InputContext {
    uintptr_t game = 0;
    uintptr_t source = 0;
    uintptr_t player = 0;
    uintptr_t map = 0;
    uintptr_t view = 0;
    uint64_t tick = 0;
    uint8_t stopped = 0;
    bool paused = false;
};

bool validInputContextConfig(const FmLinuxInputContextConfig &config);
// Fresh reads only at a verified frontend/evaluation safe point. Does not call Lua or infer simulation progress.
// Metadata code/data must have been checked against the loaded image before use. Handler and script candidate
// fields are accepted only when they refer to the independently selected current Map and input source.
int readInputContext(const FmLinuxInputContextConfig &config, const uint32_t *cancel, InputContext &output);
#endif
