#pragma once
#include <stdint.h>

// Every location is derived from the selected executable; there is no built-in Lua object layout.
typedef struct FmLinuxLuaStateLayout {
    uint32_t allocationSize;
    uint32_t globalOffset;
    uint32_t global;
    uint32_t mainState;
    uint32_t top;
    uint32_t stackBase;
    uint32_t stackEnd;
    uint32_t callInfo;
    uint32_t baseFrame;
    uint32_t function;
    uint32_t frameTop;
    uint32_t status;
    uint32_t handler;
    uint32_t valueSize;
} FmLinuxLuaStateLayout;

#ifdef __cplusplus
struct LuaStateObservation {
    uintptr_t handler = 0;
    uint32_t elements = 0;
    uint32_t capacity = 0;
    uint32_t frameCapacity = 0;
};

bool validLuaStateLayout(const FmLinuxLuaStateLayout &layout);
// Same frontend callback only. An active protected handler is returned, not mistaken for idle ownership.
// The caller checks the expected element count, available capacity and handler ownership before each API call.
int readLuaState(uintptr_t state, const FmLinuxLuaStateLayout &layout, const uint32_t *cancel, LuaStateObservation &output);
#endif
