#include "lua.h"
#include "ldo.h"

#ifdef _WIN32
#define FIXTURE_EXPORT __declspec(dllexport)
#else
#define FIXTURE_EXPORT __attribute__((visibility("default")))
#endif

/* The fixture exports the internal protection boundary for native ABI-adapter tests only. */
FIXTURE_EXPORT int fm_fixture_raw_protected(lua_State *state, Pfunc callback, void *userdata) {
    return luaD_rawrunprotected(state, callback, userdata);
}
