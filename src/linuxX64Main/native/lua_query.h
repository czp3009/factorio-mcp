#pragma once
#include <stddef.h>
#include <stdint.h>
#include "lua_state.h"

#define FM_LINUX_LUA_SOURCE 131072
#define FM_LINUX_QUERY_ARGUMENTS 262144
#define FM_LINUX_QUERY_RESULT (1024 * 1024)
#define FM_LINUX_QUERY_ERROR 1024

// All addresses require matching executable, live code and System V ABI validation before use.
typedef struct FmLinuxLuaApi {
    uint64_t absIndex;
    uint64_t setTop;
    uint64_t load;
    uint64_t pushNumber;
    uint64_t pushString;
    uint64_t protectedCall;
    uint64_t toString;
    uint64_t rawProtected;
    uint32_t protectedCallArguments;
} FmLinuxLuaApi;

typedef struct FmLinuxLuaQuery {
    uint32_t sourceSize;
    uint32_t argumentsSize;
    uint32_t includeViewport;
    char source[FM_LINUX_LUA_SOURCE];
    char arguments[FM_LINUX_QUERY_ARGUMENTS];
} FmLinuxLuaQuery;

typedef struct FmLinuxLuaResult {
    uint32_t size;
    uint32_t errorSize;
    char text[FM_LINUX_QUERY_RESULT];
    char error[FM_LINUX_QUERY_ERROR];
} FmLinuxLuaResult;

#ifdef __cplusplus
struct QueryViewport {
    double surface = 0;
    double width = 0;
    double height = 0;
    double left = 0;
    double top = 0;
    double right = 0;
    double bottom = 0;
    const char *error = nullptr;
    size_t errorSize = 0;
};

// Caller resolves the script in this frontend callback and validates live entry/layout evidence.
// This adapter checks state identity, protected ownership and stack progress/capacity throughout execution.
// The player index is already the native-observed index converted to Lua's one-based numbering.
int queryLua(void *state, const FmLinuxLuaApi &api, const FmLinuxLuaStateLayout &layout, const FmLinuxLuaQuery &query, double playerIndex,
             const QueryViewport &viewport, const uint32_t *cancel, FmLinuxLuaResult &result);
#endif
