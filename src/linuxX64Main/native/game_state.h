#pragma once
#include <stdint.h>

enum { FM_LINUX_LOADING_STATES = 256, FM_LINUX_MULTIPLAYER_BINDINGS = 4 };

typedef struct FmLinuxVirtualBoolean {
    uint64_t table;
    uint64_t typeInfo;
    uint64_t function;
    uint32_t adjustment;
    uint32_t slot;
} FmLinuxVirtualBoolean;

typedef struct FmLinuxManagerBoolean {
    uint64_t primary;
    uint32_t member;
    uint32_t size;
    FmLinuxVirtualBoolean predicate;
} FmLinuxManagerBoolean;

typedef struct FmLinuxGameStateConfig {
    uint64_t global;
    uint32_t globalSize;
    uint32_t scenarioSize;
    uint32_t gameSize;
    uint32_t mapSize;
    uint32_t scenario;
    uint32_t game;
    uint32_t map;
    uint32_t paused;
    uint32_t stopped;
    uint32_t appManager;
    uint32_t appManagerSize;
    uint32_t statesBegin;
    uint32_t statesEnd;
    uint32_t stateCount;
    FmLinuxVirtualBoolean states[FM_LINUX_LOADING_STATES];
    uint32_t managerCount;
    FmLinuxManagerBoolean managers[FM_LINUX_MULTIPLAYER_BINDINGS];
} FmLinuxGameStateConfig;

typedef struct FmLinuxGameState {
    uint32_t state;
    int32_t paused;
} FmLinuxGameState;

#ifdef __cplusplus
bool validGameStateConfig(const FmLinuxGameStateConfig &config);
int readGameState(const FmLinuxGameStateConfig &config, const uint32_t *cancel, FmLinuxGameState &output);
#endif
