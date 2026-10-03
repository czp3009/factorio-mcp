#pragma once
#include "world_objects.h"
#include "player_objects.h"
#include "lua_query.h"
#include "viewport.h"

typedef struct FmLinuxWorldQueryConfig {
    FmLinuxWorldLayout world;
    FmLinuxScriptLayout script;
    FmLinuxPlayerLayout player;
    FmLinuxLuaStateLayout state;
    FmLinuxLuaApi api;
    FmLinuxViewportLayout viewport;
} FmLinuxWorldQueryConfig;

#ifdef __cplusplus
// Execute only in the verified frontend callback; all references are reacquired for this command.
int queryWorld(const FmLinuxWorldQueryConfig &config, const FmLinuxLuaQuery &query, const uint32_t *cancel,
               FmLinuxLuaResult &result);
#endif
