#include "key_state.h"
#include "keyboard_fixture.h"
#include <cassert>
#include <cerrno>
#include <cstddef>
#include <cstring>
#include <limits>
#include <initializer_list>
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
    uint16_t modifiers = 0;
};

struct Global {
    uint64_t padding{};
    State *state = nullptr;
};

struct Event {
    uint32_t type, code;
    double time;
    uint16_t modifiers;
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
    active->state->modifiers = event.modifiers;
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
    FmLinuxMouseStateLayout owner{reinterpret_cast<uintptr_t>(&root),
                                  sizeof(Global),
                                  offsetof(Global, state),
                                  sizeof(State),
                                  offsetof(State, buttons),
                                  sizeof(Event),
                                  offsetof(Event, type),
                                  offsetof(Event, time),
                                  offsetof(Event, code),
                                  7,
                                  9,
                                  {1, 2, 3},
                                  {2, 4, 8}};
    const FmLinuxKeyStateLayout layout{offsetof(State, keys),
                                       offsetof(Map, begin),
                                       offsetof(Map, end),
                                       sizeof(Record),
                                       offsetof(Record, key),
                                       offsetof(Record, value) + offsetof(Value, held),
                                       fixtureModifiers(offsetof(State, modifiers))};
    assert(validKeyStateLayout(owner, layout));
    const uint16_t masks[]{1, 2, 0x40, 0x80, 0x100, 0x200, 0x400, 0x800, 0x4000, 0x1000, 0x2000, 0x8000};
    for (unsigned index = 0; index < 12; ++index) {
        for (bool down : {false, true}) {
            state.modifiers = 0x2222;
            uint16_t result = 0;
            assert(readKeyboardModifiers(owner, layout.modifiers, reinterpret_cast<uintptr_t>(&global),
                                         reinterpret_cast<uintptr_t>(&state), layout.modifiers.keys[index], down,
                                         result) == 0);
            const uint16_t expected = index < 9
                                          ? (down ? state.modifiers | masks[index] : state.modifiers & ~masks[index])
                                          : (down ? state.modifiers ^ masks[index] : state.modifiers);
            assert(result == expected && state.modifiers == 0x2222);
        }
    }
    uint16_t unchanged = 0;
    assert(readKeyboardModifiers(owner, layout.modifiers, reinterpret_cast<uintptr_t>(&global),
                                 reinterpret_cast<uintptr_t>(&state), 999, true, unchanged) == 0 &&
           unchanged == state.modifiers);
    assert(readKeyboardModifiers(owner, layout.modifiers, reinterpret_cast<uintptr_t>(&global),
                                 reinterpret_cast<uintptr_t>(&state) + 8, 7, true, unchanged) == ESTALE);
    state.modifiers = 0;
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
    assert(read(-3) == 0 && result.present && !result.held);
    assert(read(7) == 0 && result.present && result.held);
    assert(read(19) == 0 && result.present && !result.held);
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
    invalid.held = invalid.stride;
    assert(readKeyState(owner, invalid, 7, objects, result) == EINVAL && !result.present && !objects.state);
    invalid = layout;
    invalid.held = invalid.key;
    assert(!validKeyStateLayout(owner, invalid));
    invalid = layout;
    invalid.modifiers.state = invalid.map + invalid.begin;
    assert(!validKeyStateLayout(owner, invalid));
    invalid = layout;
    invalid.modifiers.keys[1] = invalid.modifiers.keys[0];
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
    const FmLinuxKeyboardStateConfig config{layout,     offsetof(Event, code), offsetof(Event, modifiers), 11, 13,
                                            {7, 19, 31}};
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
        KeyboardKeyOwnership key;
        records[1].value.held = 1;
        assert(key.press(owner, config, functions, 7, 0) == 0 && key.owned());
        assert(key.release(owner, config, functions, 1) == 0 && !key.owned() && !records[1].value.held);
        KeyboardKeyOwnership blocked;
        assert(blocked.press(owner, config, functions, 19, 0) == 0 && blocked.owned());
        assert(blocked.release(owner, config, functions, 1) == 0 && !blocked.owned() && !records[2].value.held);
        const auto count = updates;
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
        assert(key.press(owner, config, functions, 7, 0) == 0 && key.owned());
        assert(key.release(owner, config, functions, 1) == 0 && !key.owned());
        assert(inserted.value.held == 0 && inserted.value.blocked == 0);
    }
}
