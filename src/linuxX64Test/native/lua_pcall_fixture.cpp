#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <initializer_list>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

struct Value {
    uint64_t data[2];
};

struct Frame {
    unsigned char padding[FIXTURE_PADDING];
    Value *function;
    Value *top;
};

struct State {
    unsigned char padding[FIXTURE_PADDING];
    unsigned char status;
    Value *top;
    Frame *frame;
    Value *base;
};

struct Record {
    unsigned char padding[FIXTURE_PADDING];
    Value *function;
    int results;
};

using Continuation = void (*)();
static volatile int protectedResult = 37;

extern "C" {
extern const uint64_t fixture_state_size = sizeof(State);
extern const uint64_t fixture_status_offset = offsetof(State, status);
extern const uint64_t fixture_top_offset = offsetof(State, top);
extern const uint64_t fixture_frame_offset = offsetof(State, frame);
extern const uint64_t fixture_base_offset = offsetof(State, base);
extern const uint64_t fixture_function_offset = offsetof(Frame, function);
extern const uint64_t fixture_limit_offset = offsetof(Frame, top);
extern const uint64_t fixture_record_function = offsetof(Record, function);
extern const uint64_t fixture_record_results = offsetof(Record, results);

__attribute__((noinline, noreturn)) void fixture_abort() {
    std::abort();
}

__attribute__((noinline)) void fixture_callback(State *state, void *userdata) {
    const auto *record = static_cast<Record *>(userdata);
    if (record->function != state->base + 1 || record->results != 1) fixture_abort();
}

__attribute__((noinline)) int fixture_protected(State *state, void (*callback)(State *, void *), void *userdata,
                                               ptrdiff_t oldTop, ptrdiff_t error) {
    if (oldTop != sizeof(Value) || error != 0) fixture_abort();
    callback(state, userdata);
    return protectedResult;
}

__attribute__((noinline)) int fixture_pcall5(State *state, int nargs, int results, int error, Continuation continuation) {
    if (continuation) fixture_abort();
    if (state->top - state->frame->function <= nargs + 1) fixture_abort();
    if (state->status != 0) fixture_abort();
    if (results != -1 && state->frame->top - state->top < results - nargs) fixture_abort();
    if (error != 0) fixture_abort();
    Record record;
    record.function = state->top - (nargs + 1);
    record.results = results;
    return fixture_protected(state, fixture_callback, &record,
        reinterpret_cast<char *>(record.function) - reinterpret_cast<char *>(state->base), 0);
}

__attribute__((noinline)) int fixture_pcall6(State *state, int nargs, int results, int error, intptr_t context,
                                           Continuation continuation) {
    if (continuation) fixture_abort();
    if (context != 0) fixture_abort();
    if (state->top - state->frame->function <= nargs + 1) fixture_abort();
    if (state->status != 0) fixture_abort();
    if (results != -1 && state->frame->top - state->top < results - nargs) fixture_abort();
    if (error != 0) fixture_abort();
    Record record;
    record.function = state->top - (nargs + 1);
    record.results = results;
    return fixture_protected(state, fixture_callback, &record,
        reinterpret_cast<char *>(record.function) - reinterpret_cast<char *>(state->base), 0);
}
}

int main() {
    Value values[32]{};
    Frame frame{};
    frame.function = values;
    frame.top = values + 32;
    State state{};
    state.frame = &frame;
    state.base = values;
    for (const auto nargs : {2, 10}) {
        state.top = values + nargs + 2;
        if (fixture_pcall5(&state, nargs, 1, 0, nullptr) != 37 ||
            fixture_pcall6(&state, nargs, 1, 0, 0, nullptr) != 37) return 1;
    }
    return 0;
}
