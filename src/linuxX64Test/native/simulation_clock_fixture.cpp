#include <cassert>
#include <cstddef>
#include <cstdint>
#include <limits>

extern "C" void fixture_clock_stop();
extern "C" void fixture_clock_paused();
extern "C" unsigned fixture_clock_paused_calls;
struct lua_State;
struct Map;

struct StopLock {
    Map *map;
    __attribute__((noinline)) void release();

    ~StopLock() {
        release();
    }
};

struct MapTick {
    std::uint64_t value;

    __attribute__((always_inline)) double toLuaDouble() const {
        return value == std::numeric_limits<std::uint64_t>::max() ? -1.0 : static_cast<double>(value);
    }
};

struct Map {
    std::uint64_t padding[FIXTURE_PADDING];
    MapTick tick;
    std::uint8_t stopLevel;
    bool paused;

    __attribute__((always_inline)) bool isStopped() const {
        return stopLevel != 0;
    }

    __attribute__((noinline)) StopLock stop(bool) {
        if (!isStopped())
            fixture_clock_stop();
        ++stopLevel;
        return {this};
    }

    __attribute__((noinline)) void stopPermanently(bool value) {
        auto lock = stop(value);
        ++stopLevel;
    }
};

void StopLock::release() {
    if (map) {
        --map->stopLevel;
        map = nullptr;
    }
}

struct LuaGameScript {
    std::uint64_t padding[FIXTURE_PADDING + 1];
    Map *map;

    __attribute__((noinline)) double luaReadTick(lua_State *) {
        return map->tick.toLuaDouble();
    }
};

struct InputSource {
    virtual ~InputSource() = default;

    virtual void sendPausedStateChanges() {
        fixture_clock_paused();
    }
};

struct Game {
    std::uint64_t padding[FIXTURE_PADDING + 2];
    Map *map;
    InputSource *source;

    __attribute__((noinline)) void updateInputSource() {
        if (map->paused)
            source->sendPausedStateChanges();
        fixture_clock_stop();
    }
};

extern "C" {
extern const std::size_t fixture_map_size = sizeof(Map);
extern const std::size_t fixture_script_size = sizeof(LuaGameScript);
extern const std::size_t fixture_map_tick = offsetof(Map, tick);
extern const std::size_t fixture_map_stop = offsetof(Map, stopLevel);
extern const std::size_t fixture_script_map = offsetof(LuaGameScript, map);
extern const std::size_t fixture_game_map = offsetof(Game, map);
extern const std::size_t fixture_game_source = offsetof(Game, source);
extern const std::size_t fixture_map_paused = offsetof(Map, paused);
extern const std::size_t fixture_game_size = sizeof(Game);
}

int main() {
    Map map{};
    LuaGameScript script{};
    script.map = &map;
    map.tick.value = 42;
    assert(script.luaReadTick(nullptr) == 42.0);
    map.tick.value = std::numeric_limits<std::uint64_t>::max();
    assert(script.luaReadTick(nullptr) == -1.0);
    {
        auto lock = map.stop(false);
        assert(map.isStopped());
    }
    assert(!map.isStopped());
    map.stopPermanently(false);
    assert(map.stopLevel == 1);
    InputSource source;
    Game game{};
    game.map = &map;
    game.source = &source;
    game.updateInputSource();
    assert(fixture_clock_paused_calls == 0);
    map.paused = true;
    game.updateInputSource();
    assert(fixture_clock_paused_calls == 1);
    map.paused = false;
    game.updateInputSource();
    assert(fixture_clock_paused_calls == 1);
}
