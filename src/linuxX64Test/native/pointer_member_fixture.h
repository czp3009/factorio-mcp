#pragma once
#include <cstdint>

struct Text {
    mutable unsigned calls;
    void update() const;
};

struct State {
    const Text* value;
};

extern "C" void fixture_push(State* state, const Text* value);
