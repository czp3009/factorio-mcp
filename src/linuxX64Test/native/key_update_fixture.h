#pragma once
#include <cstddef>
#include <cstdint>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

struct FixtureKeyState {
    std::uint8_t prefix[FIXTURE_PADDING + 1]{};
    std::uint8_t held = 0;
    std::uint64_t used = 0;
    std::uint8_t blocked = 0;
};

struct FixtureKeyMap { FixtureKeyState values[3]; };
struct FixtureKeyOwner {
    std::uint64_t padding[FIXTURE_PADDING]{};
    FixtureKeyMap keys;
    std::uint32_t other = 0;
};
struct FixtureKeyEvent {
    std::uint8_t prefix[FIXTURE_PADDING + 3]{};
    std::uint32_t type = 0;
    double time = 0;
    std::uint32_t code = 0;
};

extern "C" FixtureKeyState *fixture_key_lookup(FixtureKeyMap *, std::uint32_t);
