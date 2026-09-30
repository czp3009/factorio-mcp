#pragma once
#include <stdint.h>

#define FM_LINUX_CONTEXT_CANDIDATES 64
#define FM_LINUX_SCRIPT_NAME 64

typedef struct FmLinuxWorldLayout {
    uint64_t global;
    uint64_t contextVtable;
    uint64_t contextTypeInfo;
    uint32_t globalSize;
    uint32_t scenarioSize;
    uint32_t gameSize;
    uint32_t contextSize;
    uint32_t scenario;
    uint32_t game;
    uint32_t contextCount;
    uint32_t contexts[FM_LINUX_CONTEXT_CANDIDATES];
} FmLinuxWorldLayout;

typedef struct FmLinuxScriptLayout {
    uint64_t vtable;
    uint64_t typeInfo;
    uint32_t contextSize;
    uint32_t scriptSize;
    uint32_t begin;
    uint32_t end;
    uint32_t name;
    uint32_t stringData;
    uint32_t stringLength;
    uint32_t expectedSize;
    uint8_t expected[FM_LINUX_SCRIPT_NAME];
    uint32_t state;
    uint32_t loading;
    uint32_t enabled;
} FmLinuxScriptLayout;

#ifdef __cplusplus
// Callback-local references only. Never store these in IPC or retain them between frames.
struct WorldObjects {
    uintptr_t scenario = 0;
    uintptr_t game = 0;
    uintptr_t context = 0;
    uintptr_t global = 0;
};

bool validWorldLayout(const FmLinuxWorldLayout &layout);
// Call only at the verified frontend safe point. Success establishes references, not Lua readiness or pause state.
int readWorldObjects(const FmLinuxWorldLayout &layout, const uint32_t *cancel, WorldObjects &output);

struct ScriptObjects {
    uintptr_t script = 0;
    uintptr_t state = 0;
};

bool validScriptLayout(const FmLinuxScriptLayout &layout);
// The context must have been resolved in this same frontend callback. Checks native load flags, but does not call Lua.
int readDefaultScript(uintptr_t context, const FmLinuxScriptLayout &layout, const uint32_t *cancel, ScriptObjects &output);
#endif
