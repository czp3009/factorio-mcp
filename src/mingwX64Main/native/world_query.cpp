#include "world_query.h"
#include "protocol.h"
#include "viewport.h"
#include <algorithm>
#include <cstring>

namespace {
template <class T> T function(const Symbols &symbols, FmSymbol id) {
    return reinterpret_cast<T>(symbols.address[id]);
}

template <class T> T read(void *object, unsigned offset) {
    T value;
    memcpy(&value, static_cast<unsigned char *>(object) + offset, sizeof(value));
    return value;
}
} // namespace

void queryWorld(const Symbols &symbols, void *game, const FmWorldQuery &query, FmResult &result) {
    const auto &layout = symbols.world;
    require(layout.supported && game, "World query requires an initialized world adapter");
    require(query.sourceSize && query.sourceSize < FM_MAX_LUA_SOURCE && query.argumentsSize &&
                query.argumentsSize < FM_MAX_QUERY_ARGUMENTS,
            "Invalid world query payload size");
    void *player = function<void *(*)(void *)>(symbols, LocalPlayer)(game);
    require(player, "The injected client has no local player");
    void *scenario = read<void *>(game, layout.scenario);
    require(scenario, "The current world has no scenario");
    void *context = function<void *(*)(void *)>(symbols, LuaContext)(scenario);
    require(context, "World Lua context is unavailable");
    void *script = function<void *(*)(void *)>(symbols, DefaultScript)(context);
    require(script, "Default world script is unavailable");
    require(read<bool>(script, layout.setupFinished) && !read<bool>(script, layout.runningOnLoad),
            "World Lua setup is incomplete");
    void *state =
        function<void *(*)(void *)>(symbols, LuaStateCast)(static_cast<unsigned char *>(script) + layout.luaState);
    require(state, "World Lua state is unavailable");
    auto getTop = function<int (*)(void *)>(symbols, LuaGetTop);
    const int top = getTop(state);
    require(top == 0, "World Lua state is busy");
    require(function<int (*)(void *, int)>(symbols, LuaCheckStack)(state, 16), "Cannot reserve query stack space");

    struct Restore {
        void *state;
        int top;
        void (*setTop)(void *, int);

        ~Restore() {
            setTop(state, top);
        }
    } restore{state, top, function<void (*)(void *, int)>(symbols, LuaSetTop)};

    int status = function<int (*)(void *, const char *, size_t, const char *, const char *)>(symbols, LuaLoadBuffer)(
        state, query.source, query.sourceSize, "=factorio-mcp-world-query", "t");
    if (!status) {
        // The game's LuaPlayer index reader maps the native zero-based uint16 index to Lua's one-based index.
        function<void (*)(void *, double)>(symbols, LuaPushNumber)(
            state, double(read<uint16_t>(player, layout.playerIndex)) + 1);
        function<const char *(*)(void *, const char *, size_t)>(symbols, LuaPushString)(state, query.arguments,
                                                                                        query.argumentsSize);
        if (query.includeViewport) {
            ViewportSnapshot view{};
            std::string failure;
            try {
                view = readViewport(symbols, player);
            } catch (const std::exception &error) {
                failure = std::string(error.what()).substr(0, 512);
            }
            for (double value : {double(view.surface), double(view.width), double(view.height), view.left, view.top,
                                 view.right, view.bottom})
                function<void (*)(void *, double)>(symbols, LuaPushNumber)(state, value);
            function<const char *(*)(void *, const char *, size_t)>(symbols, LuaPushString)(state, failure.data(),
                                                                                            failure.size());
        }
        status = function<int (*)(void *, int, int, int, int, void *)>(symbols, LuaProtectedCall)(
            state, query.includeViewport ? 10 : 2, 1, 0, 0, nullptr);
    }
    size_t size{};
    const char *text = function<const char *(*)(void *, int, size_t *)>(symbols, LuaToString)(state, -1, &size);
    if (status) {
        throw std::runtime_error(text ? std::string(text, std::min(size, size_t(1024))) : "World Lua query failed");
    }
    require(text && size && size < FM_MAX_WORLD_JSON, "World query output exceeds its bound or is not JSON text");
    memcpy(result.worldJson, text, size);
    result.worldJson[size] = 0;
    result.worldSize = static_cast<uint32_t>(size);
}
