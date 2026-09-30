#include "key_update_fixture.h"
#include <cassert>

extern "C" {
extern const std::uint64_t fixture_key_owner_size = sizeof(FixtureKeyOwner);
extern const std::uint64_t fixture_key_map = offsetof(FixtureKeyOwner, keys);
extern const std::uint64_t fixture_key_held = offsetof(FixtureKeyState, held);
extern const std::uint64_t fixture_key_used = offsetof(FixtureKeyState, used);
extern const std::uint64_t fixture_key_blocked = offsetof(FixtureKeyState, blocked);
extern const std::uint64_t fixture_key_event_size = sizeof(FixtureKeyEvent);
extern const std::uint64_t fixture_key_event_type = offsetof(FixtureKeyEvent, type);
extern const std::uint64_t fixture_key_event_time = offsetof(FixtureKeyEvent, time);
extern const std::uint64_t fixture_key_event_code = offsetof(FixtureKeyEvent, code);
extern const std::uint64_t fixture_key_press = FIXTURE_PADDING + 2;
extern const std::uint64_t fixture_key_release = FIXTURE_PADDING + 6;
extern const std::uint64_t fixture_key_modifier_code = FIXTURE_PADDING % 3;
}

extern "C" __attribute__((noinline)) void fixture_key_update(FixtureKeyOwner *owner, const FixtureKeyEvent *event) {
    switch (event->type - FIXTURE_PADDING) {
        case 0:
            owner->other = event->code;
            break;
        case 1:
            owner->other *= event->code;
            break;
        case 2:
            fixture_key_lookup(&owner->keys, event->code)->held = 1;
            break;
        case 3:
            owner->other += event->code;
            break;
        case 4:
            owner->other ^= event->code;
            break;
        case 5:
            owner->other &= event->code;
            break;
        case 6: {
            auto *key = fixture_key_lookup(&owner->keys, event->code);
            key->held = 0;
            key->used = 0;
            key->blocked = 0;
            break;
        }
        case 7:
            owner->other |= event->code;
            break;
        case 8:
            owner->other -= event->code;
            break;
        default:
            break;
    }
}

extern "C" __attribute__((noinline)) void fixture_key_post(FixtureKeyOwner *owner, const FixtureKeyEvent *event) {
    if (event->type == fixture_key_release) {
        fixture_key_lookup(&owner->keys, event->code)->prefix[0] = 0;
        fixture_key_lookup(&owner->keys, event->code)->prefix[1] = 0;
    }
}

extern "C" __attribute__((noinline)) bool fixture_key_modifier(FixtureKeyOwner *owner) {
    return fixture_key_lookup(&owner->keys, fixture_key_modifier_code)->held == 1 &&
        fixture_key_lookup(&owner->keys, fixture_key_modifier_code)->prefix[1] != 1;
}

int main() {
    FixtureKeyOwner owner;
    for (std::uint32_t code = 0; code < 3; ++code) {
        FixtureKeyEvent event;
        event.code = code;
        event.type = fixture_key_press;
        fixture_key_update(&owner, &event);
        assert(owner.keys.values[code].held == 1);
        owner.keys.values[code].prefix[0] = 1;
        owner.keys.values[code].prefix[1] = 1;
        fixture_key_post(&owner, &event);
        assert(owner.keys.values[code].prefix[0] == 1 && owner.keys.values[code].prefix[1] == 1);
        owner.keys.values[code].used = 123;
        owner.keys.values[code].blocked = 1;
        event.type = fixture_key_release;
        fixture_key_update(&owner, &event);
        assert(owner.keys.values[code].held == 0 && owner.keys.values[code].used == 0 &&
            owner.keys.values[code].blocked == 0);
        fixture_key_post(&owner, &event);
        assert(owner.keys.values[code].prefix[0] == 0 && owner.keys.values[code].prefix[1] == 0);
    }
    auto &modifier = owner.keys.values[fixture_key_modifier_code];
    assert(!fixture_key_modifier(&owner));
    modifier.held = 1;
    assert(fixture_key_modifier(&owner));
    modifier.prefix[1] = 1;
    assert(!fixture_key_modifier(&owner));
}
