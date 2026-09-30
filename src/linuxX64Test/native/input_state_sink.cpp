#include "input_state_fixture.h"

extern "C" unsigned fixture_state_mask(const FixtureState *state) {
    return state->mask;
}

extern "C" void fixture_event_copy_slow(FixtureStateEvent *target, const FixtureStateEvent *source) {
    *target = *source;
}

extern "C" unsigned fixture_observe_event(const FixtureStateEvent *event) {
    static volatile unsigned observed;
    observed = event->code;
    return 0;
}
