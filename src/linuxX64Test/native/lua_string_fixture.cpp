#include <cstddef>
#include <cstdint>
#include <cstring>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

struct State {
    unsigned char padding[FIXTURE_PADDING]{};
    bool collect{};
    unsigned collections{};
    const char *bytes{};
    size_t size{};
    unsigned pushed{};
};

extern "C" {
extern const uint64_t fixture_state_size = sizeof(State);

__attribute__((noinline)) void fixture_collect(State *state) {
    state->collections++;
    // Model a normal external System V call, including caller-saved register destruction.
    asm volatile("" ::: "rax", "rcx", "rdx", "rsi", "rdi", "r8", "r9", "r10", "r11", "memory");
}

__attribute__((noinline)) void fixture_construct(State *state, const char *bytes, size_t size) {
    state->bytes = bytes;
    state->size = size;
}

__attribute__((noinline)) void fixture_push(State *state, const char *bytes, size_t size) {
    if (state->collect)
        fixture_collect(state);
    fixture_construct(state, bytes, size);
    state->pushed++;
}
}

int main() {
    State state;
    const char input[] = {'a', 0, 'b'};
    fixture_push(&state, input, sizeof(input));
    if (state.collections || state.pushed != 1 || state.bytes != input || state.size != sizeof(input))
        return 1;
    state.collect = true;
    fixture_push(&state, input, sizeof(input));
    return state.collections == 1 && state.pushed == 2 && state.bytes == input &&
                   state.size == sizeof(input) && !std::memcmp(state.bytes, input, sizeof(input)) ? 0 : 2;
}
