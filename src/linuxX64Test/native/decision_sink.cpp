#include "decision_fixture.h"
#include <cstdlib>

extern "C" DecisionEntry *fixture_decision_lookup(DecisionOwner *owner, unsigned key) {
    ++owner->calls;
    if (key == FIXTURE_PADDING + 3)
        return &owner->entries[0];
    if (key == FIXTURE_PADDING + 9)
        return &owner->entries[1];
    std::abort();
}

extern "C" DecisionEntry *fixture_decision_refresh(DecisionOwner *owner, unsigned key) {
    ++owner->calls;
    return fixture_decision_lookup(owner, key);
}

extern "C" unsigned fixture_decision_emit(bool value) {
    return value;
}
