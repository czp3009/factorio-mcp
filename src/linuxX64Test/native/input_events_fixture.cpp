#include "input_events.h"
#include <cassert>
#include <cerrno>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <sys/mman.h>
#include <unistd.h>

namespace {
struct State {
    uint64_t prefix[3]{};
    uint32_t held{};
};

struct Global {
    uint64_t prefix[5]{};
    State *state{};
};

struct Event {
    uint64_t unused{};
    uint32_t code{};
    uint32_t type{};
    double time{};
};

Global *root;
Global replacement;
State replacementState;
unsigned updates, posts;
bool failUpdate, failPost, replaceOnUpdate, replaceOnPost, ignoreUpdate;
Event observed;

void update(void *pointer, const void *input) {
    ++updates;
    assert(pointer == root->state);
    std::memcpy(&observed, input, sizeof(observed));
    assert(observed.unused == 0 && std::isfinite(observed.time));
    auto &state = *static_cast<State *>(pointer);
    if (!ignoreUpdate) {
        const auto mask = 1u << observed.code;
        if (observed.type == 31)
            state.held |= mask;
        else {
            assert(observed.type == 47);
            state.held &= ~mask;
        }
    }
    if (replaceOnUpdate)
        root = &replacement;
    if (failUpdate)
        throw std::runtime_error("fixture update failure");
}

void post(void *pointer, const void *input) {
    ++posts;
    assert(pointer == root->state);
    assert(std::memcmp(input, &observed, sizeof(observed)) == 0);
    if (replaceOnPost)
        root = &replacement;
    if (failPost)
        throw std::runtime_error("fixture post failure");
}
} // namespace

int main() {
    State state;
    Global global;
    global.state = &state;
    root = &global;
    replacement.state = &replacementState;
    const FmLinuxMouseStateLayout layout{
        reinterpret_cast<uintptr_t>(&root), sizeof(Global), offsetof(Global, state), sizeof(State), offsetof(State, held),
        sizeof(Event), offsetof(Event, type), offsetof(Event, time), offsetof(Event, code), 31, 47, {1, 3, 2}, {2, 8, 4}};
    const InputStateFunctions functions{update, post};
    assert(validMouseStateLayout(layout));
    InputStateObjects objects;
    assert(readInputState(layout, objects) == 0);
    assert(objects.global == reinterpret_cast<uintptr_t>(&global) && objects.state == reinterpret_cast<uintptr_t>(&state));
    InputDispatch progress;
    state.held = 1u << 7;
    for (const auto code : layout.codes) {
        assert(dispatchMouseState(layout, functions, objects, code, true, 1.25, progress) == 0);
        assert(progress.updateEntered && progress.updateReturned && progress.postEntered && progress.postReturned);
        assert(state.held == ((1u << 7) | (1u << code)) && observed.time == 1.25);
        assert(dispatchMouseState(layout, functions, objects, code, false, 2.5, progress) == 0);
        assert(state.held == (1u << 7) && observed.time == 2.5);
    }
    const auto before = updates;
    assert(dispatchMouseState(layout, functions, objects, 9, true, 0, progress) == EINVAL);
    assert(!progress.updateEntered && !progress.postEntered && updates == before);
    assert(dispatchMouseState(layout, functions, objects, 1, true, std::numeric_limits<double>::quiet_NaN(), progress) == EINVAL);
    assert(dispatchMouseState(layout, functions, objects, 1, true, -1, progress) == EINVAL);
    assert(dispatchMouseState(layout, {nullptr, post}, objects, 1, true, 0, progress) == EINVAL);
    ignoreUpdate = true;
    assert(dispatchMouseState(layout, functions, objects, 1, true, 0, progress) == 0);
    assert(state.held == (1u << 7)); // Dispatch completion does not assert an effect or retry.
    ignoreUpdate = false;
    failUpdate = true;
    assert(dispatchMouseState(layout, functions, objects, 1, true, 0, progress) == EIO);
    assert(progress.updateEntered && !progress.updateReturned && progress.postReturned);
    assert(state.held & 2);
    failUpdate = false;
    assert(dispatchMouseState(layout, functions, objects, 1, false, 0, progress) == 0);
    failPost = true;
    assert(dispatchMouseState(layout, functions, objects, 1, true, 0, progress) == EIO);
    assert(progress.updateReturned && progress.postEntered && !progress.postReturned);
    failPost = false;
    assert(dispatchMouseState(layout, functions, objects, 1, false, 0, progress) == 0);
    replaceOnUpdate = true;
    const auto postsBefore = posts;
    assert(dispatchMouseState(layout, functions, objects, 1, true, 0, progress) == ESTALE);
    assert(progress.updateReturned && !progress.postEntered && posts == postsBefore);
    assert(dispatchMouseState(layout, functions, objects, 1, false, 0, progress) == ESTALE);
    assert(!progress.updateEntered);
    root = &global;
    replaceOnUpdate = false;
    replaceOnPost = true;
    assert(dispatchMouseState(layout, functions, objects, 1, false, 0, progress) == ESTALE);
    assert(progress.updateReturned && progress.postReturned);
    root = &global;
    replaceOnPost = false;
    failUpdate = true;
    replaceOnUpdate = true;
    assert(dispatchMouseState(layout, functions, objects, 1, true, 0, progress) == EIO);
    assert(!progress.updateReturned && !progress.postEntered); // Preserve the original update failure.
    root = &global;
    failUpdate = false;
    replaceOnUpdate = false;
    auto invalid = layout;
    invalid.eventCode = invalid.eventTime;
    assert(!validMouseStateLayout(invalid));
    invalid = layout;
    invalid.stateMember = sizeof(Global);
    assert(!validMouseStateLayout(invalid));
    invalid = layout;
    invalid.masks[1] = invalid.masks[0];
    assert(!validMouseStateLayout(invalid));
    invalid = layout;
    invalid.codes[0] = 256;
    assert(!validMouseStateLayout(invalid));
    const auto page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    auto *guard = mmap(nullptr, page, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(guard != MAP_FAILED);
    global.state = static_cast<State *>(guard);
    assert(readInputState(layout, objects) == EFAULT && !objects.state && !objects.global && !objects.held);
    global.state = nullptr;
    assert(readInputState(layout, objects) == ENOENT);
    root = nullptr;
    assert(readInputState(layout, objects) == ENOENT);
    assert(munmap(guard, page) == 0);

    root = &global;
    global.state = &state;
    state.held = 1u << 7;
    for (const auto code : layout.codes) {
        MouseButtonOwnership button;
        assert(button.press(layout, functions, code, 3) == 0 && button.owned());
        assert(button.press(layout, functions, code, 3) == EALREADY);
        assert(state.held == ((1u << 7) | (1u << code)));
        assert(button.release(layout, functions, 4) == 0 && !button.owned());
        const auto settled = updates;
        assert(button.release(layout, functions, 5) == 0 && updates == settled && state.held == (1u << 7));
    }
    state.held |= 2;
    MouseButtonOwnership foreign;
    const auto foreignBefore = updates;
    assert(foreign.press(layout, functions, 1, 3) == EBUSY && !foreign.owned() && updates == foreignBefore);
    assert(foreign.release(layout, functions, 4) == EBUSY && (state.held & 2) && updates == foreignBefore);
    state.held &= ~2u;
    MouseButtonOwnership throwingPress;
    failUpdate = true;
    assert(throwingPress.press(layout, functions, 1, 3) == EIO && throwingPress.owned() && (state.held & 2));
    failUpdate = false;
    assert(throwingPress.release(layout, functions, 4) == EIO && !throwingPress.owned() && !(state.held & 2));
    MouseButtonOwnership throwingRelease;
    assert(throwingRelease.press(layout, functions, 1, 3) == 0);
    failUpdate = true;
    assert(throwingRelease.release(layout, functions, 4) == EIO && !throwingRelease.owned());
    failUpdate = false;
    MouseButtonOwnership uncertain;
    assert(uncertain.press(layout, functions, 1, 3) == 0);
    ignoreUpdate = true;
    assert(uncertain.release(layout, functions, 4) == EPROTO && uncertain.owned());
    const auto uncertainBefore = updates;
    ignoreUpdate = false;
    assert(uncertain.release(layout, functions, 5) == EPROTO && uncertain.owned() && updates == uncertainBefore);
    state.held &= ~2u; // A separately observed release reconciles ownership without replaying the uncertain event.
    assert(uncertain.release(layout, functions, 6) == EPROTO && !uncertain.owned() && updates == uncertainBefore);
    MouseButtonOwnership replaced;
    assert(replaced.press(layout, functions, 1, 3) == 0);
    root = &replacement;
    replacementState.held = 2;
    const auto replacementBefore = updates;
    assert(replaced.release(layout, functions, 4) == ESTALE && !replaced.owned() &&
        replacementState.held == 2 && updates == replacementBefore);
    root = &global;
    state.held &= ~2u;
    MouseButtonOwnership unavailable;
    assert(unavailable.press(layout, functions, 1, 3) == 0);
    global.state = static_cast<State *>(mmap(nullptr, page, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    assert(global.state != MAP_FAILED);
    assert(unavailable.release(layout, functions, 4) == EFAULT && unavailable.owned());
    assert(munmap(global.state, page) == 0);
    global.state = &state;
    assert(unavailable.release(layout, functions, 5) == EFAULT && !unavailable.owned() && !(state.held & 2));
    return 0;
}
