#include "lua_state.h"
#include <array>
#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <sys/mman.h>
#include <unistd.h>

static void require(bool value, const char *message) {
    if (!value) {
        std::fprintf(stderr, "factorio-mcp Lua state fixture: %s\n", message);
        std::abort();
    }
}

struct Value { uint64_t data[2]; };
struct Frame {
    std::array<char, 13> padding{};
    Value *function = nullptr;
    Value *top = nullptr;
};
struct Global;
struct State {
    std::array<char, 19> padding{};
    uint8_t status = 0;
    Global *global = nullptr;
    Value *base = nullptr;
    Value *top = nullptr;
    Value *end = nullptr;
    Frame *frame = nullptr;
    void *handler = nullptr;
    Frame baseFrame;
};
struct Global {
    std::array<char, 29> padding{};
    State *mainState = nullptr;
};
struct Allocation { State state; Global global; };

int main() {
    alarm(30);
    FmLinuxLuaStateLayout layout{};
    layout.allocationSize = sizeof(Allocation);
    layout.globalOffset = offsetof(Allocation, global);
    layout.global = offsetof(State, global);
    layout.mainState = offsetof(Global, mainState);
    layout.top = offsetof(State, top);
    layout.stackBase = offsetof(State, base);
    layout.stackEnd = offsetof(State, end);
    layout.callInfo = offsetof(State, frame);
    layout.baseFrame = offsetof(State, baseFrame);
    layout.function = offsetof(Frame, function);
    layout.frameTop = offsetof(Frame, top);
    layout.status = offsetof(State, status);
    layout.handler = offsetof(State, handler);
    layout.valueSize = sizeof(Value);
    require(validLuaStateLayout(layout), "valid synthetic layout");
    const auto original = layout;
    Allocation allocation;
    Value values[64]{};
    State &state = allocation.state;
    uint32_t cancel = 0;
    LuaStateObservation output;
    auto reset = [&] {
        allocation = {};
        state.global = &allocation.global;
        allocation.global.mainState = &state;
        state.base = values;
        state.top = values + 1;
        state.end = values + 64;
        state.frame = &state.baseFrame;
        state.baseFrame.function = values;
        state.baseFrame.top = values + 32;
    };
    auto read = [&] { return readLuaState(reinterpret_cast<uintptr_t>(&state), layout, &cancel, output); };
    auto fails = [&](int expected, const char *message) {
        output = {123, 123, 123, 123};
        require(read() == expected, message);
        require(!output.handler && !output.elements && !output.capacity && !output.frameCapacity, "failure clears observation");
        reset();
    };
    reset();
    require(read() == 0 && output.elements == 0 && output.capacity == 63 && output.frameCapacity == 31 && !output.handler,
        "empty native frame counts");
    state.top = values + 12;
    state.handler = &layout;
    require(read() == 0 && output.elements == 11 && output.capacity == 52 && output.frameCapacity == 20 &&
        output.handler == reinterpret_cast<uintptr_t>(&layout), "active handler and stack contents remain explicit");
    reset();
    state.global = nullptr;
    fails(ESTALE, "global identity");
    allocation.global.mainState = nullptr;
    fails(ESTALE, "main state identity");
    Frame foreign;
    state.frame = &foreign;
    fails(EBUSY, "foreign active frame");
    state.status = 1;
    fails(EBUSY, "nonzero native status");
    state.baseFrame.function = values + 1;
    fails(EFAULT, "wrong frame function");
    state.top = values;
    fails(EFAULT, "missing frame function slot");
    state.top = reinterpret_cast<Value *>(reinterpret_cast<uintptr_t>(values) + sizeof(Value) + 1);
    fails(EFAULT, "misaligned element difference");
    state.top = values + 33;
    fails(EFAULT, "top beyond call frame");
    state.end = values + 31;
    fails(EFAULT, "call frame beyond stack capacity");
    state.base = reinterpret_cast<Value *>(std::numeric_limits<uintptr_t>::max() - 7);
    fails(EFAULT, "wrapping base");
    cancel = 1;
    fails(ECANCELED, "cancellation");
    cancel = 0;
    for (auto *field : {&layout.global, &layout.top, &layout.stackBase, &layout.stackEnd, &layout.callInfo,
                       &layout.baseFrame, &layout.function, &layout.frameTop, &layout.status, &layout.handler, &layout.mainState}) {
        *field = layout.allocationSize;
        fails(EINVAL, "out of bounds layout");
        layout = original;
    }
    layout.status = layout.top;
    fails(EINVAL, "status overlaps pointer");
    layout = original;
    layout.handler = layout.top;
    fails(EINVAL, "pointer members overlap");
    layout = original;
    layout.valueSize = 24;
    fails(EINVAL, "unsupported element stride");
    layout = original;
    const long page = sysconf(_SC_PAGESIZE);
    void *guard = mmap(nullptr, page, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    require(guard != MAP_FAILED, "guard page allocation");
    require(readLuaState(reinterpret_cast<uintptr_t>(guard), layout, &cancel, output) == EFAULT, "unreadable state");
    munmap(guard, page);
    require(readLuaState(std::numeric_limits<uintptr_t>::max() - 7, layout, &cancel, output) == EFAULT, "wrapping state");
    require(readLuaState(reinterpret_cast<uintptr_t>(&state), layout, nullptr, output) == EINVAL, "missing cancellation");
    return 0;
}
