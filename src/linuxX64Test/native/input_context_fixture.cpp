#include "input_task_context.h"
#include <cassert>
#include <cerrno>
#include <cstring>
#include <limits>
#include <sys/mman.h>
#include <unistd.h>

namespace {
struct Game;
struct Map {
    uint64_t tick = std::numeric_limits<uint64_t>::max();
    Game *game = nullptr;
    uint8_t stopped = 0;
    uint8_t paused = 0;
};
struct Player {
    virtual ~Player() = default;
    Map *map = nullptr;
};
struct Source {
    virtual ~Source() = default;
    Player *player = nullptr;
};
struct View {
    virtual ~View() = default;
    Player *player = nullptr;
};
struct Handler {
    virtual ~Handler() = default;
    Map *map = nullptr;
    Source *source = nullptr;
};
struct Game {
    View *view = nullptr;
    Handler *handler = nullptr;
    void *other = nullptr;
    Source *source = nullptr;
};
struct Name {
    const char *data;
    uint64_t length;
};
struct Script {
    virtual ~Script() = default;
    Name name{"fixture", 7};
    uintptr_t *state = nullptr;
    uint8_t loading = 0;
    uint8_t enabled = 1;
    Map *map = nullptr;
};
struct Context {
    virtual ~Context() = default;
    Script **begin = nullptr;
    Script **end = nullptr;
};
struct Scenario {
    Game *game;
    Context *context;
};
struct Global {
    Scenario *scenario;
    Source *source;
};

uint32_t offset(const void *object, const void *member) {
    return static_cast<const char *>(member) - static_cast<const char *>(object);
}

void identity(const void *object, uint64_t &table, uint64_t &type) {
    std::memcpy(&table, object, sizeof(table));
    std::memcpy(&type, reinterpret_cast<const void *>(table - 8), sizeof(type));
}
} // namespace

int main() {
    alarm(30);
    Map map;
    Player player, otherPlayer;
    Source source, otherSource;
    View view;
    Handler handler;
    Game game{&view, &handler, &source, &source}, otherGame;
    map.game = &game;
    player.map = &map;
    source.player = &player;
    view.player = &player;
    handler.map = &map;
    handler.source = &source;
    Script script;
    uintptr_t state = 0;
    script.state = &state;
    script.map = &map;
    Script *scripts[] = {&script};
    Context context;
    context.begin = scripts;
    context.end = scripts + 1;
    Scenario scenario{&game, &context};
    Global global{&scenario, &source};
    Global *root = &global;
    FmLinuxInputContextConfig config{};
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
    auto &input = config.input;
    identity(&source, input.sourceVtable, input.sourceTypeInfo);
    identity(&player, input.playerVtable, input.playerTypeInfo);
    identity(&view, input.viewVtable, input.viewTypeInfo);
    identity(&handler, input.handlerVtable, input.handlerTypeInfo);
    input.sourceSize = sizeof(source);
    input.playerSize = sizeof(player);
    input.mapSize = sizeof(map);
    input.viewSize = sizeof(view);
    input.handlerSize = sizeof(handler);
    input.globalSource = offset(&global, &global.source);
    input.sourcePlayer = offset(&source, &source.player);
    input.playerMap = offset(&player, &player.map);
    input.mapGame = offset(&map, &map.game);
    input.gameView = offset(&game, &game.view);
    input.gameSource = offset(&game, &game.source);
    input.viewPlayer = offset(&view, &view.player);
    input.scriptMap = offset(&script, &script.map);
    input.mapTick = offset(&map, &map.tick);
    input.mapStop = offset(&map, &map.stopped);
    input.mapPaused = offset(&map, &map.paused);
    input.handlerMap = offset(&handler, &handler.map);
    input.handlerSource = offset(&handler, &handler.source);
    input.handlerCount = 2;
    input.handlers[0] = offset(&game, &game.handler);
    input.handlers[1] = offset(&game, &game.other);
    uint32_t cancel = 0;
    InputContext output;
    auto read = [&] { return readInputContext(config, &cancel, output); };
    auto reject = [&](int error) {
        output = {1, 1, 1, 1, 1, 1, 1, true};
        assert(read() == error);
        assert(!output.game && !output.source && !output.player && !output.map && !output.view && !output.tick &&
               !output.stopped && !output.paused);
    };
    assert(read() == 0 && output.game == reinterpret_cast<uintptr_t>(&game) &&
           output.source == reinterpret_cast<uintptr_t>(&source) && output.tick == map.tick &&
           output.view == reinterpret_cast<uintptr_t>(&view));
    map.tick = 0;
    map.stopped = 255;
    map.paused = 1;
    assert(read() == 0 && output.tick == 0 && output.stopped == 255 && output.paused);
    map.paused = 2;
    reject(EPROTO);
    map.paused = 0;
    map.stopped = 0;
    cancel = 1;
    reject(ECANCELED);
    cancel = 0;
    root = nullptr;
    reject(ENOENT);
    root = &global;
    script.loading = 1;
    reject(EAGAIN);
    script.loading = 0;
    global.source = nullptr;
    reject(ENOENT);
    global.source = reinterpret_cast<Source *>(&view);
    reject(ENOTSUP);
    global.source = &source;
    game.source = nullptr;
    reject(ESTALE);
    game.source = &otherSource;
    reject(ESTALE);
    game.source = &source;
    source.player = &otherPlayer;
    reject(ESTALE);
    source.player = &player;
    view.player = nullptr;
    reject(ESTALE);
    view.player = &player;
    map.game = &otherGame;
    reject(ESTALE);
    map.game = &game;
    script.map = nullptr;
    reject(ESTALE);
    script.map = &map;
    handler.map = nullptr;
    reject(ESTALE);
    handler.map = &map;
    handler.source = &otherSource;
    reject(ESTALE);
    handler.source = &source;
    game.handler = nullptr;
    reject(ENOTSUP);
    game.handler = &handler;
    game.other = &handler;
    reject(ENOTUNIQ);
    game.other = &source;
    input.handlerTypeInfo += 8;
    reject(ESTALE);
    input.handlerTypeInfo -= 8;
    const auto original = config;
    input.gameSource = input.gameView;
    reject(EINVAL);
    config = original;
    input.gameSource = config.world.gameSize - 1;
    reject(EINVAL);
    config = original;
    input.handlers[1] = input.gameSource;
    reject(EINVAL);
    config = original;
    input.mapTick = input.mapSize - 1;
    reject(EINVAL);
    config = original;
    input.mapPaused = input.mapTick;
    reject(EINVAL);
    config = original;
    input.handlerSource = input.handlerMap;
    reject(EINVAL);
    config = original;
    input.scriptMap = lua.state;
    reject(EINVAL);
    config = original;
    input.handlers[1] = input.handlers[0];
    reject(EINVAL);
    config = original;
    const size_t pageSize = sysconf(_SC_PAGESIZE);
    void *guard = mmap(nullptr, pageSize, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(guard != MAP_FAILED);
    game.other = guard;
    reject(EFAULT);
    game.other = nullptr;
    player.map = static_cast<Map *>(guard);
    reject(EFAULT);
    player.map = &map;
    assert(munmap(guard, pageSize) == 0);
    assert(read() == 0 && !output.paused && !output.stopped);
    ViewLifetime lifetime;
    InputTaskContext task(lifetime);
    assert(task.bind(config, &cancel, output) == 0);
    ++map.tick;
    assert(task.read(output) == 0 && output.tick == map.tick);
    // Both the source and view can coherently select another Player while the world remains unchanged.
    otherPlayer.map = &map;
    source.player = &otherPlayer;
    view.player = &otherPlayer;
    assert(read() == 0 && output.player == reinterpret_cast<uintptr_t>(&otherPlayer));
    assert(task.read(output) == ESTALE && !output.player && task.owned());
    source.player = &player;
    view.player = &player;
    assert(task.read(output) == ESTALE);
    task.release();
    InputTaskContext sourceTask(lifetime);
    assert(sourceTask.bind(config, &cancel, output) == 0);
    otherSource.player = &player;
    global.source = &otherSource;
    game.source = &otherSource;
    handler.source = &otherSource;
    assert(read() == 0 && output.source == reinterpret_cast<uintptr_t>(&otherSource));
    assert(sourceTask.read(output) == ESTALE && !output.source && sourceTask.owned());
    sourceTask.release();
}
