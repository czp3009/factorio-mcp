#include "game_state.h"
#include <cassert>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <stdexcept>

struct Predicate {
    uint8_t value = 0;
    virtual uint8_t observe() const {
        if (value == 255)
            throw std::runtime_error("fixture");
        return value;
    }
};

struct Primary {
    virtual ~Primary() = default;
    uintptr_t padding[FIXTURE_PADDING]{};
};

struct Manager : Primary, Predicate {};

struct Map {
    uintptr_t padding[FIXTURE_PADDING]{};
    uint8_t paused = 0;
    uint8_t stopped = 0;
};

struct Game {
    uintptr_t padding[FIXTURE_PADDING]{};
    Map *map = nullptr;
};

struct Scenario {
    uintptr_t padding[FIXTURE_PADDING]{};
    Game *game = nullptr;
};

struct AppManager {
    uintptr_t padding[FIXTURE_PADDING]{};
    Predicate **begin = nullptr;
    Predicate **end = nullptr;
};

struct Global {
    uintptr_t padding[FIXTURE_PADDING]{};
    AppManager *app = nullptr;
    Scenario *scenario = nullptr;
    Manager *first = nullptr;
    Manager *second = nullptr;
};

static FmLinuxVirtualBoolean predicate(Predicate &instance, uint32_t adjustment = 0) {
    uintptr_t table;
    std::memcpy(&table, &instance, sizeof(table));
    const auto member = &Predicate::observe;
    struct Representation {
        intptr_t function;
        intptr_t adjustment;
    } representation;
    static_assert(sizeof(member) == sizeof(representation));
    std::memcpy(&representation, &member, sizeof(member));
    assert(representation.function > 0 && representation.function % sizeof(uintptr_t) == 1 &&
        representation.adjustment == 0);
    const auto slot = static_cast<uint32_t>((representation.function - 1) / sizeof(uintptr_t));
    const auto *words = reinterpret_cast<const uintptr_t *>(table);
    return {table, words[-1], words[slot], adjustment, slot};
}

int main() {
    Predicate idle;
    Predicate *states[]{&idle};
    Manager first, second;
    AppManager app;
    app.begin = states;
    app.end = states + 1;
    Map map;
    Game game;
    game.map = &map;
    Scenario scenario;
    scenario.game = &game;
    Global root;
    root.app = &app;
    Global *global = &root;
    FmLinuxGameStateConfig config{};
    config.global = reinterpret_cast<uintptr_t>(&global);
    config.globalSize = sizeof(Global);
    config.scenarioSize = sizeof(Scenario);
    config.gameSize = sizeof(Game);
    config.mapSize = sizeof(Map);
    config.scenario = offsetof(Global, scenario);
    config.game = offsetof(Scenario, game);
    config.map = offsetof(Game, map);
    config.paused = offsetof(Map, paused);
    config.stopped = offsetof(Map, stopped);
    config.appManager = offsetof(Global, app);
    config.appManagerSize = sizeof(AppManager);
    config.statesBegin = offsetof(AppManager, begin);
    config.statesEnd = offsetof(AppManager, end);
    config.stateCount = 1;
    config.states[0] = predicate(idle);
    const auto adjustment = reinterpret_cast<uintptr_t>(static_cast<Predicate *>(&first)) -
        reinterpret_cast<uintptr_t>(&first);
    const auto primary = *reinterpret_cast<const uintptr_t *>(&first);
    config.managerCount = 2;
    config.managers[0] = {primary, offsetof(Global, first), sizeof(Manager), predicate(first, adjustment)};
    config.managers[1] = {primary, offsetof(Global, second), sizeof(Manager), predicate(second, adjustment)};
    assert(validGameStateConfig(config));
    uint32_t cancel = 0;
    FmLinuxGameState snapshot;
    auto read = [&](uint32_t expected, int paused) {
        assert(readGameState(config, &cancel, snapshot) == 0);
        assert(snapshot.state == expected && snapshot.paused == paused);
    };
    read(1, 0);
    root.scenario = &scenario;
    read(2, 0);
    map.paused = 1;
    read(4, 1);
    map.paused = 0;
    map.stopped = 7;
    read(4, 1);
    map.stopped = 0;
    idle.value = 1;
    read(3, 0);
    idle.value = 0;
    root.first = &first;
    root.second = &second;
    second.value = 1;
    read(2, 0);
    root.first = nullptr;
    read(3, 0);
    root.second = nullptr;
    map.paused = 2;
    assert(readGameState(config, &cancel, snapshot) == ERANGE);
    assert(snapshot.state == 0 && snapshot.paused == -1);
    map.paused = 0;
    idle.value = 3;
    assert(readGameState(config, &cancel, snapshot) == ERANGE);
    idle.value = 255;
    assert(readGameState(config, &cancel, snapshot) == EIO);
    idle.value = 0;
    const auto original = config;
    ++config.states[0].function;
    assert(readGameState(config, &cancel, snapshot) == ESTALE);
    config = original;
    config.states[0].typeInfo += sizeof(uintptr_t);
    assert(readGameState(config, &cancel, snapshot) == ESTALE);
    config = original;
    config.states[0].table += sizeof(uintptr_t);
    assert(readGameState(config, &cancel, snapshot) == ENOTSUP);
    config = original;
    config.stateCount = 0;
    assert(readGameState(config, &cancel, snapshot) == EINVAL);
    config = original;
    app.end = reinterpret_cast<Predicate **>(reinterpret_cast<uintptr_t>(app.begin) +
        (FM_LINUX_LOADING_STATES + 1) * sizeof(uintptr_t));
    assert(readGameState(config, &cancel, snapshot) == ERANGE);
    app.end = states + 1;
    cancel = 1;
    assert(readGameState(config, &cancel, snapshot) == ECANCELED);
    cancel = 0;
    global = nullptr;
    assert(readGameState(config, &cancel, snapshot) == EFAULT);
    assert(snapshot.state == 0 && snapshot.paused == -1);
}
