#include <cstddef>
#include <cstdint>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

struct Record {
    unsigned char padding[FIXTURE_PADDING]{};
    void *function{};
    int results{};
};

struct State {
    void *function{};
    int results{};
    int yield{};
    unsigned calls{};
};

extern "C" {
extern const uint64_t fixture_function_offset = offsetof(Record, function);
extern const uint64_t fixture_results_offset = offsetof(Record, results);

__attribute__((noinline)) void fixture_dispatch(State *state, void *function, int results, int yield) {
    state->function = function;
    state->results = results;
    state->yield = yield;
    state->calls++;
}

__attribute__((noinline)) void fixture_call(State *state, void *userdata) {
    const auto *record = static_cast<Record *>(userdata);
    fixture_dispatch(state, record->function, record->results, 0);
}
}

int main() {
    State state;
    int function;
    Record record;
    record.function = &function;
    record.results = 3;
    fixture_call(&state, &record);
    return state.function == &function && state.results == 3 && !state.yield && state.calls == 1 ? 0 : 1;
}
