#include <cstdint>

struct Value;
struct State {
    std::uint64_t padding[FIXTURE_PADDING];
    Value* top;
    bool grow;
};

extern "C" void fixture_boolean_grow(State* state) {
    state->grow = false;
}
