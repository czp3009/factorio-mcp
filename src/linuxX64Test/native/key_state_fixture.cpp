#include "key_state.h"
#include <cassert>
#include <cerrno>
#include <cstddef>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <sys/mman.h>
#include <unistd.h>

struct Value {
    uint64_t padding[2]{};
    uint8_t held = 0;
    uint8_t blocked = 0;
};
struct Record {
    uint64_t padding{};
    int32_t key{};
    Value value;
};
struct Map {
    uint64_t padding[2]{};
    Record *begin = nullptr;
    Record *end = nullptr;
};
struct State {
    uint32_t buttons = 0;
    Map keys;
};
struct Global {
    uint64_t padding{};
    State *state = nullptr;
};
struct Event {
    uint32_t type, code;
    double time;
};

namespace {
Global *active;
Global *replacement;
Record inserted;
unsigned updates = 0, posts = 0;
bool failUpdate = false, failPost = false, ignoreRelease = false, replaceUpdate = false, blockInserted = false;

void update(void *receiver, const void *bytes) {
    ++updates;
    assert(receiver == active->state);
    Event event;
    std::memcpy(&event, bytes, sizeof(event));
    assert(event.type == 11 || event.type == 13);
    auto &map = active->state->keys;
    Record *record = nullptr;
    for (auto *cursor = map.begin; cursor != map.end; ++cursor)
        if (cursor->key == int32_t(event.code))
            record = cursor;
    if (!record) {
        assert(map.begin == map.end);
        inserted = {};
        inserted.key = int32_t(event.code);
        inserted.value.blocked = blockInserted;
        map.begin = &inserted;
        map.end = &inserted + 1;
        record = &inserted;
    }
    if (event.type == 11)
        record->value.held = 1;
    else if (!ignoreRelease)
        record->value.held = 0;
    if (replaceUpdate)
        active = replacement;
    if (failUpdate)
        throw std::runtime_error("fixture key update failed");
}

void post(void *receiver, const void *bytes) {
    ++posts;
    assert(receiver == active->state);
    Event event;
    std::memcpy(&event, bytes, sizeof(event));
    if (event.type == 13)
        for (auto *cursor = active->state->keys.begin; cursor != active->state->keys.end; ++cursor)
            if (cursor->key == int32_t(event.code))
                cursor->value.blocked = 0;
    if (failPost)
        throw std::runtime_error("fixture key post-update failed");
}
} // namespace

int main() {
    State state;
    Global global{0, &state};
    Global *root = &global;
    FmLinuxMouseStateLayout owner{
        reinterpret_cast<uintptr_t>(&root), sizeof(Global), offsetof(Global, state), sizeof(State), offsetof(State, buttons),
        sizeof(Event), offsetof(Event, type), offsetof(Event, time), offsetof(Event, code), 7, 9, {1, 2, 3}, {2, 4, 8}};
    const FmLinuxKeyStateLayout layout{offsetof(State, keys), offsetof(Map, begin), offsetof(Map, end), sizeof(Record),
        offsetof(Record, key), offsetof(Record, value), sizeof(Value), offsetof(Value, held), offsetof(Value, blocked)};
    assert(validKeyStateLayout(owner, layout));
    InputStateObjects objects;
    KeyStateValue result;
    auto read = [&](int32_t code) { return readKeyState(owner, layout, code, objects, result); };
    assert(read(7) == 0 && !result.present && objects.state == reinterpret_cast<uintptr_t>(&state));
    Record records[3]{};
    records[0].key = -3;
    records[1].key = 7;
    records[2].key = 19;
    records[1].value.held = 1;
    records[2].value.blocked = 1;
    state.keys = {{}, records, records + 3};
    Record original[3];
    std::memcpy(original, records, sizeof(records));
    assert(read(-3) == 0 && result.present && !result.held && !result.blocked);
    assert(read(7) == 0 && result.present && result.held && !result.blocked);
    assert(read(19) == 0 && result.present && !result.held && result.blocked);
    assert(read(11) == 0 && !result.present);
    assert(std::memcmp(original, records, sizeof(records)) == 0);
    records[2].key = 7;
    assert(read(7) == EPROTO && !result.present && objects.state == 0);
    records[2].key = 19;
    records[1].value.held = 2;
    assert(read(7) == EPROTO && !result.present);
    records[1].value.held = 1;
    state.keys.end = records;
    assert(read(7) == 0 && !result.present);
    state.keys.end = reinterpret_cast<Record *>(reinterpret_cast<uintptr_t>(records) + 1);
    assert(read(7) == EPROTO);
    state.keys.end = reinterpret_cast<Record *>(reinterpret_cast<uintptr_t>(records) +
        sizeof(Record) * (FM_LINUX_KEY_RECORDS + 1ULL));
    assert(read(7) == EPROTO);
    auto invalid = layout;
    invalid.held = invalid.clear;
    assert(readKeyState(owner, invalid, 7, objects, result) == EINVAL && !result.present && !objects.state);
    invalid = layout;
    invalid.value = invalid.key;
    assert(!validKeyStateLayout(owner, invalid));
    const auto page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    void *guard = mmap(nullptr, page, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(guard != MAP_FAILED);
    state.keys.begin = static_cast<Record *>(guard);
    state.keys.end = reinterpret_cast<Record *>(reinterpret_cast<uintptr_t>(guard) + sizeof(Record));
    assert(read(7) == EFAULT && !result.present && !objects.state);
    assert(munmap(guard, page) == 0);
    global.state = nullptr;
    assert(read(7) == ENOENT);
    root = nullptr;
    assert(read(7) == ENOENT);

    global.state = &state;
    active = &global;
    owner.global = reinterpret_cast<uintptr_t>(&active);
    state.keys = {{}, records, records + 3};
    records[1].value.held = 0;
    records[0].value.held = 1;
    const FmLinuxKeyboardStateConfig config{layout, offsetof(Event, code), 11, 13, {7, 19, 31}};
    const InputStateFunctions functions{update, post};
    assert(validKeyboardStateConfig(owner, config));
    {
        KeyboardKeyOwnership key;
        assert(key.press(owner, config, functions, 7, 1.25) == 0 && key.owned());
        assert(key.pressProgress().updateReturned && key.pressProgress().postReturned);
        assert(key.press(owner, config, functions, 7, 1.25) == EALREADY);
        assert(records[1].value.held == 1);
        assert(key.release(owner, config, functions, 2.5) == 0 && !key.owned());
        assert(records[1].value.held == 0 && records[0].value.held == 1);
        const auto count = updates;
        assert(key.release(owner, config, functions, 3) == 0 && updates == count);
    }
    {
        const auto count = updates;
        KeyboardKeyOwnership key;
        records[1].value.held = 1;
        assert(key.press(owner, config, functions, 7, 0) == EBUSY && !key.owned() && updates == count);
        records[1].value.held = 0;
        KeyboardKeyOwnership blocked;
        assert(blocked.press(owner, config, functions, 19, 0) == EBUSY && !blocked.owned() && updates == count);
        KeyboardKeyOwnership unknown;
        assert(unknown.press(owner, config, functions, 42, 0) == EINVAL && !unknown.owned() && updates == count);
        KeyboardKeyOwnership badTime;
        assert(badTime.press(owner, config, functions, 7, std::numeric_limits<double>::quiet_NaN()) == EINVAL &&
            !badTime.owned() && updates == count);
    }
    {
        KeyboardKeyOwnership key;
        failUpdate = true;
        assert(key.press(owner, config, functions, 7, 0) == EIO && key.owned());
        assert(key.pressProgress().updateEntered && !key.pressProgress().updateReturned);
        failUpdate = false;
        assert(key.release(owner, config, functions, 1) == EIO && !key.owned() && records[1].value.held == 0);
    }
    {
        KeyboardKeyOwnership key;
        assert(key.press(owner, config, functions, 7, 0) == 0);
        failPost = true;
        assert(key.release(owner, config, functions, 1) == EIO && !key.owned());
        assert(key.releaseProgress().updateReturned && !key.releaseProgress().postReturned);
        failPost = false;
    }
    {
        KeyboardKeyOwnership key;
        assert(key.press(owner, config, functions, 7, 0) == 0);
        ignoreRelease = true;
        assert(key.release(owner, config, functions, 1) == EPROTO && key.owned());
        const auto count = updates;
        ignoreRelease = false;
        assert(key.release(owner, config, functions, 2) == EPROTO && key.owned() && updates == count);
        records[1].value.held = 0;
        assert(key.reconcile(owner, config) == EPROTO && !key.owned() && updates == count);
    }
    {
        KeyboardKeyOwnership key;
        assert(key.press(owner, config, functions, 7, 0) == 0);
        const auto count = updates;
        active = nullptr;
        assert(key.reconcile(owner, config) == ESTALE && !key.owned() && updates == count);
        active = &global;
        records[1].value.held = 0;
    }
    {
        State otherState;
        Global other{0, &otherState};
        replacement = &other;
        replaceUpdate = true;
        KeyboardKeyOwnership key;
        const auto count = posts;
        assert(key.press(owner, config, functions, 7, 0) == ESTALE && key.owned());
        assert(posts == count);
        assert(key.reconcile(owner, config) == ESTALE && !key.owned());
        replaceUpdate = false;
        active = &global;
        records[1].value.held = 0;
    }
    for (bool blocked : {false, true}) {
        state.keys = {};
        blockInserted = blocked;
        KeyboardKeyOwnership key;
        assert(key.press(owner, config, functions, 7, 0) == (blocked ? EPROTO : 0) && key.owned());
        assert(key.release(owner, config, functions, 1) == (blocked ? EPROTO : 0) && !key.owned());
        assert(inserted.value.held == 0 && inserted.value.blocked == 0);
    }
}
