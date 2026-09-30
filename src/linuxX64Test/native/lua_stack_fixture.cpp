#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

struct Value {
    unsigned char bytes[FIXTURE_PADDING == 1 ? 16 : 32];
};

struct CallInfo {
    unsigned char padding[FIXTURE_PADDING];
    Value *function;
};

struct State {
    unsigned char padding[FIXTURE_PADDING + 8];
    CallInfo *frame;
    unsigned char gap[FIXTURE_PADDING];
    Value *top;
    Value *end;
};

extern "C" {
extern const uint64_t fixture_state_size = sizeof(State);
extern const uint64_t fixture_frame_size = sizeof(CallInfo);
extern const uint64_t fixture_top_offset = offsetof(State, top);
extern const uint64_t fixture_end_offset = offsetof(State, end);
extern const uint64_t fixture_frame_offset = offsetof(State, frame);
extern const uint64_t fixture_function_offset = offsetof(CallInfo, function);
extern const uint64_t fixture_value_size = sizeof(Value);

__attribute__((noinline)) int fixture_absindex(State *state, int index) {
    if (index > 0 || index <= -1001000)
        return index;
    return int((reinterpret_cast<uintptr_t>(state->top) - reinterpret_cast<uintptr_t>(state->frame->function)) /
               sizeof(Value)) + index;
}

__attribute__((noinline)) int fixture_mutating_absindex(State *state, int index) {
    state->padding[0] = 1;
    return fixture_absindex(state, index);
}

__attribute__((noinline)) void fixture_settop(State *state, int index) {
    Value *base = state->frame->function + 1;
    if (index >= 0) {
        if (index > state->end - base)
            std::abort();
        Value *target = base + index;
        while (state->top < target)
            *state->top++ = Value{};
        state->top = target;
    } else {
        state->top += index + 1;
    }
}

__attribute__((noinline)) void fixture_pushnumber(State *state, double number) {
    const auto available = reinterpret_cast<intptr_t>(state->end) - reinterpret_cast<intptr_t>(state->top);
    if (available <= static_cast<intptr_t>(sizeof(Value)))
        std::abort();
    std::memcpy(state->top->bytes, &number, sizeof(number));
    state->top++;
}
}

int main() {
    Value values[5]{};
    CallInfo frame{};
    frame.function = values;
    State state{};
    state.frame = &frame;
    state.top = values + 1;
    state.end = values + 5;
    if (fixture_absindex(&state, -1) != 0)
        return 1;
    state.top = values + 5;
    if (fixture_absindex(&state, -1) != 4 || fixture_absindex(&state, 2) != 2)
        return 2;
    values[1].bytes[0] = 17;
    fixture_settop(&state, 0);
    if (state.top != values + 1 || values[1].bytes[0] != 17)
        return 3;
    fixture_pushnumber(&state, 42.5);
    double number;
    std::memcpy(&number, values[1].bytes, sizeof(number));
    return state.top == values + 2 && number == 42.5 ? 0 : 4;
}
