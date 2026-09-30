#include "key_update_fixture.h"
#include <cassert>

extern "C" __attribute__((noinline)) FixtureKeyState *fixture_key_lookup(FixtureKeyMap *map, std::uint32_t code) {
    assert(code < 3);
    return &map->values[code];
}
