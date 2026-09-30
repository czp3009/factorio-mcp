#include "lua_query.h"
#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <unistd.h>
extern "C" {
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"
#include "lstate.h"
int fm_fixture_raw_protected(lua_State *, void (*)(lua_State *, void *), void *);
}

void testWorldQuery(void *, const FmLinuxLuaApi &, const FmLinuxLuaStateLayout &);

static void require(bool value, const char *message) {
    if (!value) {
        std::fprintf(stderr, "factorio-mcp Lua query fixture: %s\n", message);
        std::abort();
    }
}

template <class Function> static uint64_t address(Function function) {
    return reinterpret_cast<uintptr_t>(function);
}

static unsigned protectedCalls;

static int optimizedCall(lua_State *state, int arguments, int results, int error, void *continuation) {
    ++protectedCalls;
    require(!continuation, "optimized continuation must be null");
    return lua_pcall(state, arguments, results, error);
}

static uint32_t *activeCancel;

static int emptyLoad(lua_State *, lua_Reader, void *, const char *, const char *) {
    return 0;
}

static int limitedLoad(lua_State *state, lua_Reader reader, void *userdata, const char *name, const char *mode) {
    const int status = lua_load(state, reader, userdata, name, mode);
    state->base_ci.top = state->top;
    return status;
}

static void emptyNumber(lua_State *, lua_Number) {}

static void limitedNumber(lua_State *state, lua_Number number) {
    lua_pushnumber(state, number);
    state->base_ci.top = state->stack_last = state->top + 1;
}

static int unprotectedCallback(lua_State *state, void (*callback)(lua_State *, void *), void *userdata) {
    callback(state, userdata);
    return 0;
}

static int missingCallback(lua_State *, void (*)(lua_State *, void *), void *) {
    return 0;
}

static void emptyReset(lua_State *, int) {}

static const char *cancelAfterPush(lua_State *state, const char *value, size_t size) {
    const char *result = lua_pushlstring(state, value, size);
    __atomic_store_n(activeCancel, 1u, __ATOMIC_RELEASE);
    return result;
}

static const char *failPush(lua_State *state, const char *, size_t) {
    luaL_error(state, "synthetic push failure");
    return nullptr;
}

static const char *throwPush(lua_State *, const char *, size_t) {
    throw 1;
}

static int exceptionProtected(lua_State *state, void (*callback)(lua_State *, void *), void *userdata) {
    auto *previous = state->errorJmp;
    uintptr_t handler;
    state->errorJmp = reinterpret_cast<decltype(state->errorJmp)>(&handler);
    try {
        callback(state, userdata);
        state->errorJmp = previous;
        return 0;
    } catch (...) {
        state->errorJmp = previous;
        return -1;
    }
}

int main() {
    alarm(30);
    lua_State *state = luaL_newstate();
    require(state, "Lua state allocation failed");
    luaL_openlibs(state);
    // Fixture-only private Lua headers describe this test runtime, never the installed game.
    FmLinuxLuaStateLayout layout{};
    layout.globalOffset = reinterpret_cast<uintptr_t>(G(state)) - reinterpret_cast<uintptr_t>(state);
    layout.allocationSize = layout.globalOffset + sizeof(global_State);
    layout.global = offsetof(lua_State, l_G);
    layout.mainState = offsetof(global_State, mainthread);
    layout.top = offsetof(lua_State, top);
    layout.stackBase = offsetof(lua_State, stack);
    layout.stackEnd = offsetof(lua_State, stack_last);
    layout.callInfo = offsetof(lua_State, ci);
    layout.baseFrame = offsetof(lua_State, base_ci);
    layout.function = offsetof(CallInfo, func);
    layout.frameTop = offsetof(CallInfo, top);
    layout.status = offsetof(lua_State, status);
    layout.handler = offsetof(lua_State, errorJmp);
    layout.valueSize = sizeof(TValue);
    FmLinuxLuaApi api{address(lua_absindex), address(lua_settop), address(lua_load), address(lua_pushnumber),
                     address(lua_pushlstring), address(lua_pcallk), address(lua_tolstring),
                     address(fm_fixture_raw_protected), 6};
    auto query = std::make_unique<FmLinuxLuaQuery>();
    auto result = std::make_unique<FmLinuxLuaResult>();
    uint32_t cancel = 0;
    activeCancel = &cancel;
    auto source = [&](const char *value) {
        query->sourceSize = std::strlen(value);
        std::memcpy(query->source, value, query->sourceSize);
    };
    const char arguments[] = {'{', '}', 0, 'x'};
    query->argumentsSize = sizeof(arguments);
    std::memcpy(query->arguments, arguments, sizeof(arguments));
    QueryViewport viewport{};
    auto run = [&] {
        const int status = queryLua(state, api, layout, *query, 7, viewport, &cancel, *result);
        require(lua_gettop(state) == 0, "query did not restore an empty stack");
        return status;
    };
    source("local player, args = ...; assert(player == 7 and #args == 4 and args:byte(3) == 0); return '{\"ok\":true}'");
    require(run() == 0 && std::strcmp(result->text, "{\"ok\":true}") == 0, "query arguments/result differ");
    api.protectedCall = address(optimizedCall);
    api.protectedCallArguments = 5;
    require(run() == 0, "optimized protected-call ABI failed");
    query->includeViewport = 1;
    viewport = {2, 800, 600, -4, -3, 5, 6, "unavailable", 11};
    source("local p,a,s,w,h,l,t,r,b,e = ...; assert(p==7 and #a==4 and s==2 and w==800 and h==600 and l==-4 and t==-3 and r==5 and b==6 and e=='unavailable'); return '{}'");
    require(run() == 0, "viewport arguments differ");
    query->includeViewport = 0;
    source("error('synthetic execution failure')");
    require(run() == EPROTO && result->size == 0 && std::strstr(result->error, "synthetic execution failure"),
            "execution error was not bounded/reported");
    source("error(string.rep('x', 2048))");
    require(run() == EPROTO && result->errorSize == FM_LINUX_QUERY_ERROR - 1 &&
                result->error[FM_LINUX_QUERY_ERROR - 1] == 0, "error text was not bounded");
    source("invalid Lua !");
    require(run() == EPROTO && result->errorSize, "parse error was not reported");
    source("return nil");
    require(run() == EPROTO && !result->size && !result->errorSize, "non-text result was accepted or retained old output");
    source("return string.rep('x', 1048576)");
    require(run() == EOVERFLOW && !result->size, "oversized result was accepted");
    source("return '{}'");
    api.pushString = address(failPush);
    require(run() == EPROTO && !result->size, "unprotected argument failure escaped or retained a result");
    api.pushString = address(throwPush);
    api.rawProtected = address(exceptionProtected);
    require(run() == EPROTO && !result->size, "C++ exception did not unwind through the query callback");
    api.rawProtected = address(fm_fixture_raw_protected);
    api.pushString = address(cancelAfterPush);
    require(run() == ECANCELED && !result->size && !result->errorSize, "admitted cancellation did not clean up");
    api.pushString = address(lua_pushlstring);
    require(run() == ECANCELED, "pre-admission cancellation was ignored");
    cancel = 0;
    require(run() == 0, "query failed after cancellation/error cleanup");
    const auto calls = protectedCalls;
    api.load = address(emptyLoad);
    require(run() == EPROTO && protectedCalls == calls, "missing loaded function reached execution");
    api.load = address(limitedLoad);
    auto frameCapacity = state->base_ci.top - state->stack;
    require(run() == ENOBUFS && protectedCalls == calls, "changed frame capacity reached execution");
    state->base_ci.top = state->stack + frameCapacity;
    api.load = address(lua_load);
    api.pushNumber = address(emptyNumber);
    require(run() == EPROTO && protectedCalls == calls, "missing pushed value reached execution");
    api.pushNumber = address(limitedNumber);
    const auto capacity = state->stack_last - state->stack;
    require(run() == ENOBUFS && protectedCalls == calls, "changed stack capacity reached execution");
    state->stack_last = state->stack + capacity;
    state->base_ci.top = state->stack + frameCapacity;
    api.pushNumber = address(lua_pushnumber);
    api.rawProtected = address(unprotectedCallback);
    require(run() == EPROTO && protectedCalls == calls, "missing protected handler reached execution");
    api.rawProtected = address(missingCallback);
    require(run() == EPROTO && protectedCalls == calls, "missing protected callback reported success");
    api.rawProtected = address(fm_fixture_raw_protected);
    uintptr_t foreignHandler;
    state->errorJmp = reinterpret_cast<decltype(state->errorJmp)>(&foreignHandler);
    require(run() == EBUSY && protectedCalls == calls && state->errorJmp == reinterpret_cast<decltype(state->errorJmp)>(&foreignHandler),
        "foreign protection was admitted or modified");
    state->errorJmp = nullptr;
    const auto originalLayout = layout;
    layout.handler = layout.top;
    require(run() == EINVAL && protectedCalls == calls, "overlapping state layout was admitted");
    layout = originalLayout;
    api.setTop = address(emptyReset);
    require(queryLua(state, api, layout, *query, 7, viewport, &cancel, *result) == EPROTO && !result->size && lua_gettop(state) == 1,
        "failed cleanup reported success or discarded the result without detecting retained stack state");
    api.setTop = address(lua_settop);
    lua_settop(state, 0);
    require(run() == 0, "query failed after fixture cleanup");
    lua_pushliteral(state, "busy");
    require(queryLua(state, api, layout, *query, 7, viewport, &cancel, *result) == EBUSY && lua_gettop(state) == 1,
            "busy stack was admitted or modified");
    lua_settop(state, 0);
    api.protectedCallArguments = 4;
    require(run() == EINVAL, "unverified protected-call ABI was accepted");
    api.protectedCallArguments = 5;
    testWorldQuery(state, api, layout);
    require(lua_gettop(state) == 0, "composed world query retained Lua stack entries");
    query->sourceSize = FM_LINUX_LUA_SOURCE;
    require(run() == EINVAL, "oversized source was accepted");
    lua_close(state);
    std::puts("factorio-mcp Lua query fixture: passed");
}
