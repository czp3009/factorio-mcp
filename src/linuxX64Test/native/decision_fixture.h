#pragma once
#include <cstdint>

struct DecisionEntry {
    unsigned char padding[FIXTURE_PADDING];
    std::uint8_t active;
    std::uint8_t blocked;
};

struct DecisionOwner {
    DecisionEntry entries[2];
    unsigned calls;
};

extern "C" DecisionEntry *fixture_decision_lookup(DecisionOwner *, unsigned);
extern "C" DecisionEntry *fixture_decision_refresh(DecisionOwner *, unsigned);
extern "C" unsigned fixture_decision_emit(bool);
