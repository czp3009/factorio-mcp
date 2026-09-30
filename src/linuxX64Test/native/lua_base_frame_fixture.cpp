#include <cstddef>
#include <cstdint>
#include <cstdlib>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

struct Value { uint64_t data[2]; };
struct Frame {
    unsigned char padding[FIXTURE_PADDING];
    Value *function;
    Value *top;
};
struct Global {
    unsigned char padding[FIXTURE_PADDING];
    void *(*allocator)(void *, void *, size_t, size_t);
    void *userdata;
};
struct State {
    unsigned char padding[FIXTURE_PADDING];
    Global *global;
    Value *stack;
    Value *top;
    Frame *frame;
    Frame base;
};
struct Allocation { State state; Global global; };

extern "C" {
extern const uint64_t fixture_allocation_size = sizeof(Allocation);
extern const uint64_t fixture_global_offset = offsetof(Allocation, global);
extern const uint64_t fixture_global_member = offsetof(State, global);
extern const uint64_t fixture_allocator = offsetof(Global, allocator);
extern const uint64_t fixture_userdata = offsetof(Global, userdata);
extern const uint64_t fixture_top = offsetof(State, top);
extern const uint64_t fixture_frame = offsetof(State, frame);
extern const uint64_t fixture_function = offsetof(Frame, function);
extern const uint64_t fixture_limit = offsetof(Frame, top);
extern const uint64_t fixture_embedded = offsetof(State, base);

__attribute__((noinline)) void fixture_next(State *state) {
    if (state->frame != &state->base || state->frame->function != state->stack ||
        state->top != state->stack + 1 || state->frame->top != state->stack + 16) std::abort();
}

__attribute__((noinline)) void fixture_open(State *state) {
    auto *global = state->global;
    auto *stack = static_cast<Value *>(global->allocator(global->userdata, nullptr, 0, 32 * sizeof(Value)));
    if (!stack) std::abort();
    state->stack = stack;
    state->base.function = stack;
    state->base.top = stack + 16;
    state->top = stack + 1;
    state->frame = &state->base;
    fixture_next(state);
    // Keep a direct call so the metadata boundary cannot disappear into a tail dispatch.
    asm volatile("" ::: "memory");
}
}

static void *allocate(void *userdata, void *old, size_t oldSize, size_t size) {
    if (userdata != reinterpret_cast<void *>(uintptr_t{123}) || old || oldSize) std::abort();
    return std::malloc(size);
}

int main() {
    Allocation allocation{};
    allocation.state.global = &allocation.global;
    allocation.global.allocator = allocate;
    allocation.global.userdata = reinterpret_cast<void *>(uintptr_t{123});
    fixture_open(&allocation.state);
    std::free(allocation.state.stack);
    return 0;
}
