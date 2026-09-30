#include "world_query.h"
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>

namespace {
struct Player {
    virtual ~Player() = default;
    uint16_t index = 6;
};
struct View {
    virtual ~View() = default;
    Player *player = nullptr;
};
struct Game {
    Player *player;
    View *view;
};
struct Name {
    const char *data;
    uint64_t length;
};
struct Script {
    virtual ~Script() = default;
    Name name{"fixture", 7};
    void *state = nullptr;
    uint8_t loading = 0;
    uint8_t enabled = 1;
};
struct Context {
    virtual ~Context() = default;
    Script **begin;
    Script **end;
};
struct Scenario {
    Game *game;
    Context *context;
};
struct Global {
    Scenario *scenario;
};

uint32_t offset(const void *object, const void *member) {
    return static_cast<const char *>(member) - static_cast<const char *>(object);
}

void identity(const void *object, uint64_t &table, uint64_t &type) {
    std::memcpy(&table, object, sizeof(table));
    std::memcpy(&type, reinterpret_cast<const void *>(table - sizeof(uintptr_t)), sizeof(type));
}

void require(bool value, const char *message) {
    if (!value) {
        std::fprintf(stderr, "factorio-mcp world query fixture: %s\n", message);
        std::abort();
    }
}
} // namespace

void testWorldQuery(void *state, const FmLinuxLuaApi &api, const FmLinuxLuaStateLayout &stateLayout) {
    Player player;
    View view;
    view.player = &player;
    Game game{&player, &view};
    Script script;
    script.state = state;
    Script *scripts[] = {&script};
    Context context;
    context.begin = scripts;
    context.end = scripts + 1;
    Scenario scenario{&game, &context};
    Global global{&scenario};
    Global *root = &global;
    FmLinuxWorldQueryConfig config{};
    config.api = api;
    config.state = stateLayout;
    auto &world = config.world;
    world.global = reinterpret_cast<uintptr_t>(&root);
    world.globalSize = sizeof(global);
    world.scenarioSize = sizeof(scenario);
    world.gameSize = sizeof(game);
    world.contextSize = sizeof(context);
    world.scenario = offset(&global, &global.scenario);
    world.game = offset(&scenario, &scenario.game);
    world.contextCount = 1;
    world.contexts[0] = offset(&scenario, &scenario.context);
    identity(&context, world.contextVtable, world.contextTypeInfo);
    auto &selection = config.player;
    identity(&player, selection.playerVtable, selection.playerTypeInfo);
    identity(&view, selection.viewVtable, selection.viewTypeInfo);
    selection.gameSize = sizeof(game);
    selection.playerSize = sizeof(player);
    selection.viewSize = sizeof(view);
    selection.gamePlayer = offset(&game, &game.player);
    selection.gameView = offset(&game, &game.view);
    selection.viewPlayer = offset(&view, &view.player);
    selection.index = offset(&player, &player.index);
    selection.indexWidth = sizeof(player.index);
    auto &lua = config.script;
    identity(&script, lua.vtable, lua.typeInfo);
    lua.contextSize = sizeof(context);
    lua.scriptSize = sizeof(script);
    lua.begin = offset(&context, &context.begin);
    lua.end = offset(&context, &context.end);
    lua.name = offset(&script, &script.name);
    lua.stringData = offset(&script.name, &script.name.data);
    lua.stringLength = offset(&script.name, &script.name.length);
    lua.expectedSize = 7;
    std::memcpy(lua.expected, "fixture", 7);
    lua.state = offset(&script, &script.state);
    lua.loading = offset(&script, &script.loading);
    lua.enabled = offset(&script, &script.enabled);
    auto query = std::make_unique<FmLinuxLuaQuery>();
    auto result = std::make_unique<FmLinuxLuaResult>();
    const char source[] = "local p,a=...; assert(p==7 and a=='{}'); return '{\"player\":7}'";
    query->sourceSize = sizeof(source) - 1;
    std::memcpy(query->source, source, sizeof(source) - 1);
    query->argumentsSize = 2;
    std::memcpy(query->arguments, "{}", 2);
    uint32_t cancel = 0;
    auto run = [&] { return queryWorld(config, *query, &cancel, *result); };
    require(run() == 0 && std::strcmp(result->text, "{\"player\":7}") == 0,
            "composed world/player/script/Lua query failed");
    ++selection.gameSize;
    require(run() == EINVAL && !result->size, "inconsistent world/player bounds accepted");
    --selection.gameSize;
    game.player = nullptr;
    require(run() == 0, "composed query lost GameView fallback");
    root = nullptr;
    require(run() == ENOENT && !result->size && !result->errorSize, "world change retained a previous result");
    root = &global;
    script.loading = 1;
    require(run() == EAGAIN && !result->size, "loading script was admitted");
    script.loading = 0;
    cancel = 1;
    require(run() == ECANCELED && !result->size, "composed query ignored cancellation");
    cancel = 0;
    script.name.data = "changed";
    require(run() == ENOENT && !result->size, "query chose a non-default script");
    script.name.data = "fixture";
    player.index = 7;
    require(run() == EPROTO && !result->size && result->errorSize, "query cached a previous player index");
    player.index = 6;
    require(run() == 0 && !result->errorSize, "query did not recover after native/Lua errors");
}
