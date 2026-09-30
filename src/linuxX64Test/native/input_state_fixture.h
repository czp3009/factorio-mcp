#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

struct FixtureState {
    std::uint8_t padding[FIXTURE_PADDING];
    std::vector<int> keys;
    std::vector<int> buttons;
    unsigned mask;
};

struct FixtureStateContext {
    std::uint8_t padding[FIXTURE_PADDING];
    FixtureState *state;
};

struct FixtureStateEvent {
    std::uint32_t type;
    std::uint8_t padding[FIXTURE_PADDING];
    double time;
    std::uint32_t code;
    std::uint8_t payload[28];
};

extern "C" unsigned fixture_state_mask(const FixtureState *state);
extern "C" unsigned fixture_observe_event(const FixtureStateEvent *event);
