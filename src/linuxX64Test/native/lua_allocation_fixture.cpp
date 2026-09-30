#include <cstddef>
#include <cstdlib>

using Allocator = void *(*)(void *, void *, size_t, size_t);

struct State;

struct Global {
    unsigned char padding[FIXTURE_PADDING];
    Allocator allocator;
    void *userdata;
    State *main;
};

struct State {
    unsigned char padding[FIXTURE_PADDING];
    Global *global;
    unsigned char reserve[FIXTURE_PADDING * 3];
};

struct Allocation {
    State state;
    Global global;
};

extern "C" {
extern const size_t fixture_size = sizeof(Allocation);
extern const size_t fixture_global = offsetof(State, global);
extern const size_t fixture_global_offset = offsetof(Allocation, global);
extern const size_t fixture_allocator = offsetof(Global, allocator);
extern const size_t fixture_userdata = offsetof(Global, userdata);
extern const size_t fixture_main = offsetof(Global, main);

__attribute__((noinline)) void fixture_initialize(State *state) {
    asm volatile("" : : "r"(state) : "memory");
}

__attribute__((noinline)) State *lua_newstate(Allocator allocator, void *userdata) {
    auto *allocation = static_cast<Allocation *>(allocator(userdata, nullptr, 0, sizeof(Allocation)));
    if (!allocation)
        return nullptr;
    allocation->state.global = &allocation->global;
    allocation->global.allocator = allocator;
    allocation->global.userdata = userdata;
    allocation->global.main = &allocation->state;
    fixture_initialize(&allocation->state);
    return &allocation->state;
}
}

static void *allocate(void *userdata, void *previous, size_t oldSize, size_t newSize) {
    if (!userdata || previous || oldSize || newSize != sizeof(Allocation))
        std::abort();
    ++*static_cast<unsigned *>(userdata);
    return std::malloc(newSize);
}

static void *fail(void *, void *, size_t, size_t) {
    return nullptr;
}

int main() {
    unsigned calls = 0;
    State *state = lua_newstate(allocate, &calls);
    if (!state || calls != 1 || state->global != &reinterpret_cast<Allocation *>(state)->global ||
        state->global->main != state || state->global->allocator != allocate || state->global->userdata != &calls)
        return 1;
    std::free(state);
    return lua_newstate(fail, nullptr) ? 2 : 0;
}
