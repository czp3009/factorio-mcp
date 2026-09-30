#include "decision_fixture.h"

static __attribute__((always_inline)) inline bool decide(DecisionOwner *owner) {
    if (fixture_decision_lookup(owner, FIXTURE_PADDING + 3)->active == 1 &&
        fixture_decision_refresh(owner, FIXTURE_PADDING + 3)->blocked != 1)
        return true;
    if (fixture_decision_lookup(owner, FIXTURE_PADDING + 9)->active != 1)
        return false;
    return fixture_decision_refresh(owner, FIXTURE_PADDING + 9)->blocked != 1;
}

extern "C" __attribute__((noinline)) bool fixture_decision_getter(DecisionOwner *owner) {
    return decide(owner);
}

extern "C" __attribute__((noinline)) unsigned fixture_decision_inline(DecisionOwner *owner) {
    return fixture_decision_emit(decide(owner));
}

int main() {
    for (unsigned values = 0; values < 81; ++values) {
        DecisionOwner owner{};
        owner.entries[0].active = values % 3;
        owner.entries[0].blocked = values / 3 % 3;
        owner.entries[1].active = values / 9 % 3;
        owner.entries[1].blocked = values / 27 % 3;
        const auto expected = fixture_decision_getter(&owner);
        const auto count = owner.calls;
        owner.calls = 0;
        if (fixture_decision_inline(&owner) != static_cast<unsigned>(expected) || owner.calls != count)
            return 1;
    }
    return 0;
}
