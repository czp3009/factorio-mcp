#include "aggregate_fixture.h"
#include <cstdlib>

extern "C" {
unsigned fixture_dispatch_count = 0;
}

static void dispatch(void *receiver, const AggregateEvent &event, unsigned kind) {
    if (event.kind != kind || event.source != receiver || event.previous || event.mask != 7 ||
        event.x != 23 || event.y != 41 || event.timestamp != 1.5 || event.alt || event.control || event.shift)
        std::abort();
    ++fixture_dispatch_count;
}

extern "C" void fixture_dispatch_down(void *receiver, const AggregateEvent &event) {
    dispatch(receiver, event, 19);
}

extern "C" void fixture_dispatch_up(void *receiver, const AggregateEvent &event) {
    dispatch(receiver, event, 31);
}

extern "C" bool fixture_shift() {
    return true;
}

extern "C" bool fixture_control() {
    return false;
}

extern "C" void fixture_dispatch_modifiers(void *receiver, const AggregateEvent &event) {
    if (event.source != receiver || !event.shift || event.control)
        std::abort();
    ++fixture_dispatch_count;
}

extern "C" void fixture_dispatch_packed_modifiers(void *receiver, const AggregateEvent &event) {
    const auto &input = *static_cast<AggregateInput *>(receiver);
    if (event.source != receiver || event.alt != input.alt || event.control != input.control || event.shift != input.shift)
        std::abort();
    ++fixture_dispatch_count;
}

extern "C" void fixture_dispatch_enter(void *receiver, const AggregateEvent &event) {
    const auto &gui = *static_cast<ConstructorGui *>(receiver);
    if (event.source != receiver || event.kind != 47 || event.previous != static_cast<PreviousWidget *>(gui.previous))
        std::abort();
    ++fixture_dispatch_count;
}
