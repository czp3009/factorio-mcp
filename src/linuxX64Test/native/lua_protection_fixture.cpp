#include <cstddef>
#include <cstdint>
#include <cstdio>

struct Record {
    Record *previous;
    volatile int status;
};

struct State {
    unsigned char padding[FIXTURE_PADDING];
    uint16_t counter;
    Record *handler;
};

extern "C" {
extern const size_t fixture_state_size = sizeof(State);
extern const size_t fixture_counter = offsetof(State, counter);
extern const size_t fixture_handler = offsetof(State, handler);
extern const size_t fixture_status = offsetof(Record, status);

__attribute__((noinline)) int fixture_protected(State *state, void (*callback)(State *, void *), void *userdata) {
    const auto counter = state->counter;
    Record record;
    record.status = 0;
    record.previous = state->handler;
    state->handler = &record;
    try {
        callback(state, userdata);
    } catch (...) {
        record.status = -1;
    }
    state->handler = record.previous;
    state->counter = counter;
    return record.status;
}
}

static void callback(State *state, void *userdata) {
    auto *expected = static_cast<State *>(userdata);
    if (state != expected || !state->handler || state->counter != 17)
        throw 1;
    state->counter = 5;
    if (state->padding[0])
        throw 2;
}

int main() {
    State state{};
    state.counter = 17;
    if (fixture_protected(&state, callback, &state) != 0 || state.handler || state.counter != 17)
        return 1;
    state.padding[0] = 1;
    if (fixture_protected(&state, callback, &state) != -1 || state.handler || state.counter != 17)
        return 2;
    std::puts("factorio-mcp Lua protection fixture: passed");
}
