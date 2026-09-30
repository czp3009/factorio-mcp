#include <cassert>
#include <cstddef>
#include <cstdint>
#include <initializer_list>

struct Value {
#if FIXTURE_PADDING == 1
    int boolean;
    int tag;
#else
    int tag;
    int boolean;
#endif
};

struct State {
    std::uint64_t padding[FIXTURE_PADDING];
    Value* top;
    bool grow;
};

extern "C" void fixture_boolean_grow(State* state);

__attribute__((always_inline)) inline void lua_pushboolean(State* state, bool value) {
    if (state->grow) fixture_boolean_grow(state);
    state->top->boolean = value;
    state->top->tag = 1;
    ++state->top;
}

struct Prototype {
    std::uint64_t padding[FIXTURE_PADDING + 1];
    bool enabled;
};

struct Wrapper {
    std::uint64_t padding[FIXTURE_PADDING];
    Prototype* prototype;

    __attribute__((noinline)) int read(State* state) const {
        lua_pushboolean(state, prototype->enabled);
        return 1;
    }
};

extern "C" {
extern const std::size_t fixture_wrapper_extent = sizeof(Wrapper);
extern const std::size_t fixture_prototype_extent = sizeof(Prototype);
extern const std::size_t fixture_prototype_pointer = offsetof(Wrapper, prototype);
extern const std::size_t fixture_boolean_field = offsetof(Prototype, enabled);
extern const std::size_t fixture_lua_top = offsetof(State, top);
}

int main() {
    Prototype prototype{};
    Wrapper wrapper{};
    wrapper.prototype = &prototype;
    for (const bool enabled : {false, true}) {
        for (const bool grow : {false, true}) {
            Value output{};
            State state{};
            state.top = &output;
            state.grow = grow;
            prototype.enabled = enabled;
            assert(wrapper.read(&state) == 1);
            assert(state.top == &output + 1);
            assert(output.boolean == enabled && output.tag == 1);
            assert(!state.grow);
        }
    }
}
