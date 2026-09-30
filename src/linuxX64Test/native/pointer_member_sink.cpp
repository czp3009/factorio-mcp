#include "pointer_member_fixture.h"

void Text::update() const {
    ++calls;
}

extern "C" void fixture_push(State* state, const Text* value) {
    state->value = value;
}
