#pragma once
#include "key_state.h"
#include <cstddef>

// Deliberately different from SDK codes: verifies that the resident uses the supplied typed metadata.
inline FmLinuxKeyboardModifiers fixtureModifiers(uint32_t state, uint32_t control = 7, uint32_t shift = 19,
                                                 uint32_t alt = 31) {
    return {state, {shift, 101, control, 102, alt, 103, 104, 105, 106, 107, 108, 109}};
}

struct FixtureKeyboardService {
    struct State {
        uint32_t buttons = 0;
        uint16_t modifiers = 0;
    } state;

    struct Global {
        State *state;
    } global{&state};

    Global *root = &global;

    FmLinuxMouseStateLayout owner(uint32_t extent, uint32_t type, uint32_t time, uint32_t code) {
        return {reinterpret_cast<uintptr_t>(&root),
                sizeof(Global),
                offsetof(Global, state),
                sizeof(State),
                offsetof(State, buttons),
                extent,
                type,
                time,
                code,
                3,
                4,
                {1, 2, 3},
                {2, 4, 8}};
    }

    FmLinuxKeyboardModifiers modifiers() {
        return fixtureModifiers(offsetof(State, modifiers));
    }
};
