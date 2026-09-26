#include "world_query.h"
#include <cassert>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
struct PlayerFixture {
    unsigned char padding[11];
    uint16_t index{12};
} player;

struct StateFixture {
    int top{};
} state;

struct ScriptFixture {
    unsigned char padding[17];
    StateFixture *state{&::state};
    bool ready{true}, onload{};
} script;

struct GameFixture {
    unsigned char padding[27];
    void *scenario{&script};
} game;

int loadStatus{}, callStatus{};
std::vector<double> numbers;
std::vector<std::string> strings;
const char *output = R"({"tick":17})";
size_t outputSize{};
bool allowStack{true};

void *localPlayer(void *receiver) {
    assert(receiver == &game);
    return &player;
}

void *context(void *receiver) {
    assert(receiver == &script);
    return receiver;
}

void *defaultScript(void *receiver) {
    assert(receiver == &script);
    return receiver;
}

void *cast(void *receiver) {
    assert(receiver == &script.state);
    return *static_cast<void **>(receiver);
}

int top(void *receiver) {
    assert(receiver == &state);
    return state.top;
}

void setTop(void *receiver, int value) {
    assert(receiver == &state);
    state.top = value;
}

int stack(void *receiver, int count) {
    assert(receiver == &state && count == 16);
    return allowStack;
}

int load(void *receiver, const char *source, size_t size, const char *name, const char *mode) {
    assert(receiver == &state && std::string(source, size) == "return ..." && std::string(mode) == "t");
    assert(std::string(name) == "=factorio-mcp-world-query");
    numbers.clear();
    strings.clear();
    ++state.top;
    return loadStatus;
}

void number(void *receiver, double value) {
    assert(receiver == &state);
    numbers.push_back(value);
    ++state.top;
}

const char *string(void *receiver, const char *text, size_t size) {
    assert(receiver == &state);
    strings.emplace_back(text, size);
    ++state.top;
    return text;
}

int call(void *receiver, int arguments, int results, int handler, int continuation, void *callback) {
    assert(receiver == &state && (arguments == 2 || arguments == 10) && results == 1 && handler == 0 &&
           continuation == 0 && !callback);
    assert(state.top == arguments + 1 && numbers.front() == 13 && strings.front() == "{}");
    if (arguments == 10) {
        assert(numbers.size() == 8 && numbers[1] == 0 && strings.size() == 2);
        assert(strings[1] == "Viewport adapter is unavailable");
    }
    state.top = 1;
    return callStatus;
}

const char *text(void *receiver, int at, size_t *size) {
    assert(receiver == &state && at == -1);
    *size = outputSize ? outputSize : strlen(output);
    return output;
}
} // namespace

int main() {
    Symbols symbols{};
    symbols.world = {1,
                     offsetof(GameFixture, scenario),
                     offsetof(ScriptFixture, state),
                     offsetof(ScriptFixture, ready),
                     offsetof(ScriptFixture, onload),
                     offsetof(PlayerFixture, index)};
    auto bind = [&](FmSymbol id, auto function) { symbols.address[id] = reinterpret_cast<uintptr_t>(function); };
    bind(LocalPlayer, localPlayer);
    bind(LuaContext, context);
    bind(DefaultScript, defaultScript);
    bind(LuaStateCast, cast);
    bind(LuaGetTop, top);
    bind(LuaSetTop, setTop);
    bind(LuaCheckStack, stack);
    bind(LuaLoadBuffer, load);
    bind(LuaPushNumber, number);
    bind(LuaPushString, string);
    bind(LuaProtectedCall, call);
    bind(LuaToString, text);
    FmWorldQuery query{};
    strcpy_s(query.source, "return ...");
    query.sourceSize = 10;
    strcpy_s(query.arguments, "{}");
    query.argumentsSize = 2;
    auto result = std::make_unique<FmResult>();
    queryWorld(symbols, &game, query, *result);
    assert(state.top == 0 && result->worldSize == strlen(output) && std::string(result->worldJson) == output);
    query.includeViewport = 1;
    queryWorld(symbols, &game, query, *result);
    assert(state.top == 0);
    query.includeViewport = 0;
    auto rejected = [&] {
        try {
            queryWorld(symbols, &game, query, *result);
            return false;
        } catch (const std::runtime_error &) {
            return true;
        }
    };
    loadStatus = 1;
    assert(rejected() && state.top == 0);
    loadStatus = 0;
    callStatus = 1;
    assert(rejected() && state.top == 0);
    callStatus = 0;
    outputSize = FM_MAX_WORLD_JSON;
    assert(rejected() && state.top == 0);
    outputSize = 0;
    state.top = 1;
    assert(rejected() && state.top == 1);
    state.top = 0;
    script.ready = false;
    assert(rejected() && state.top == 0);
    script.ready = true;
    script.onload = true;
    assert(rejected() && state.top == 0);
    script.onload = false;
    allowStack = false;
    assert(rejected() && state.top == 0);
    allowStack = true;
    query.sourceSize = FM_MAX_LUA_SOURCE;
    assert(rejected());
    return 0;
}
