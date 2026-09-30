#include "world_query.h"
#include <cerrno>

int queryWorld(const FmLinuxWorldQueryConfig &config, const FmLinuxLuaQuery &query, const uint32_t *cancel,
               FmLinuxLuaResult &result) {
    result.size = 0;
    result.errorSize = 0;
    if (config.world.gameSize != config.player.gameSize || config.world.contextSize != config.script.contextSize)
        return EINVAL;
    WorldObjects world;
    if (const auto error = readWorldObjects(config.world, cancel, world))
        return error;
    PlayerObjects player;
    if (const auto error = readLocalPlayer(world.game, config.player, cancel, player))
        return error;
    ScriptObjects script;
    if (const auto error = readDefaultScript(world.context, config.script, cancel, script))
        return error;
    QueryViewport viewport;
    static constexpr char unavailable[] = "Linux viewport reader is not implemented yet";
    if (query.includeViewport) {
        viewport.error = unavailable;
        viewport.errorSize = sizeof(unavailable) - 1;
    }
    return queryLua(reinterpret_cast<void *>(script.state), config.api, config.state, query,
                    static_cast<double>(player.index) + 1, viewport, cancel, result);
}
