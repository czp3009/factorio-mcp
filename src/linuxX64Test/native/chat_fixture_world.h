#pragma once
#include "chat_admission.h"
#include <cstddef>
#include <cstring>

namespace chat_fixture {
struct Map;
struct Player {
    virtual ~Player() = default;
    Map *map = nullptr;
    uint16_t index = 2;
    void *console = nullptr;
};
struct View {
    virtual ~View() = default;
    Player *player = nullptr;
};
struct Handler {
    virtual ~Handler() = default;
    uint32_t field = 0;
};
struct Context { virtual ~Context() = default; };
struct Game { Player *player; View *view; Handler *handler; };
struct Map { Game *game; };
struct Scenario { Game *game; Context *context; };
struct Global { Scenario *scenario; };

inline uint32_t offset(const void *object, const void *member) {
    return static_cast<const char *>(member) - static_cast<const char *>(object);
}

inline uintptr_t pointer(const void *address) {
    uintptr_t value;
    std::memcpy(&value, address, sizeof(value));
    return value;
}

struct World {
    Player player, replacement;
    View view;
    Handler handler;
    Context context;
    Game game{&player, &view, &handler};
    Map map{&game};
    Scenario scenario{&game, &context};
    Global global{&scenario};
    Global *root = &global;
    FmLinuxChatAdmissionConfig config{};

    World() {
        player.map = replacement.map = &map;
        view.player = &player;
        auto &world = config.world;
        world.global = reinterpret_cast<uintptr_t>(&root);
        world.globalSize = sizeof(global);
        world.scenarioSize = sizeof(scenario);
        world.gameSize = sizeof(game);
        world.contextSize = sizeof(context);
        world.contextVtable = pointer(&context);
        world.contextTypeInfo = pointer(reinterpret_cast<void *>(world.contextVtable - 8));
        world.scenario = offsetof(Global, scenario);
        world.game = offsetof(Scenario, game);
        world.contextCount = 1;
        world.contexts[0] = offsetof(Scenario, context);
        auto &local = config.player;
        local.playerVtable = pointer(&player);
        local.playerTypeInfo = pointer(reinterpret_cast<void *>(local.playerVtable - 8));
        local.viewVtable = pointer(&view);
        local.viewTypeInfo = pointer(reinterpret_cast<void *>(local.viewVtable - 8));
        local.gameSize = sizeof(game);
        local.playerSize = sizeof(player);
        local.viewSize = sizeof(view);
        local.gamePlayer = offsetof(Game, player);
        local.gameView = offsetof(Game, view);
        local.viewPlayer = offset(&view, &view.player);
        local.index = offset(&player, &player.index);
        local.indexWidth = sizeof(player.index);
        config.handlerVtable = pointer(&handler);
        config.handlerTypeInfo = pointer(reinterpret_cast<void *>(config.handlerVtable - 8));
        config.mapSize = sizeof(map);
        config.handlerSize = sizeof(handler);
        config.playerMap = offset(&player, &player.map);
        config.mapGame = offsetof(Map, game);
        config.gameHandler = offsetof(Game, handler);
        config.handlerField = offset(&handler, &handler.field);
        config.excluded = 17;
    }

    World(const World &) = delete;
    World &operator=(const World &) = delete;
};
}
