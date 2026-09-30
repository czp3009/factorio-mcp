#include "aggregate_fixture.h"
#include <cstring>

extern "C" {
extern const std::uint64_t fixture_event_extent = sizeof(AggregateEvent);
extern const std::uint64_t fixture_event_kind = offsetof(AggregateEvent, kind);
extern const std::uint64_t fixture_event_source = offsetof(AggregateEvent, source);
extern const std::uint64_t fixture_event_previous = offsetof(AggregateEvent, previous);
extern const std::uint64_t fixture_gui_previous = offsetof(ConstructorGui, previous);
extern const std::uint64_t fixture_previous_adjustment = sizeof(PreviousPrefix);
extern const std::uint64_t fixture_event_control = offsetof(AggregateEvent, control);
extern const std::uint64_t fixture_event_shift = offsetof(AggregateEvent, shift);
extern const std::uint64_t fixture_event_alt = offsetof(AggregateEvent, alt);
extern const std::uint64_t fixture_input_size = sizeof(AggregateInput);
extern const std::uint64_t fixture_input_alt = offsetof(AggregateInput, alt);
extern const std::uint64_t fixture_input_control = offsetof(AggregateInput, control);
extern const std::uint64_t fixture_input_shift = offsetof(AggregateInput, shift);
}

__attribute__((always_inline)) inline AggregateEvent createFixtureEvent(void *receiver, unsigned kind) {
    return AggregateEvent{23, 41, {}, 7, kind, 1.5, false, false, false, receiver, nullptr};
}

extern "C" __attribute__((noinline)) void fixture_construct_down(void *receiver) {
    auto event = createFixtureEvent(receiver, 19);
    fixture_dispatch_down(receiver, event);
}

extern "C" __attribute__((noinline)) void fixture_construct_up(void *receiver) {
    auto event = createFixtureEvent(receiver, 31);
    fixture_dispatch_up(receiver, event);
}

extern "C" __attribute__((noinline)) void fixture_construct_modifiers(void *receiver) {
    AggregateEvent event{};
    const auto shift = fixture_shift();
    const auto control = fixture_control();
    event.source = receiver;
    event.shift = shift;
    event.control = control;
    fixture_dispatch_modifiers(receiver, event);
}

extern "C" __attribute__((noinline)) void fixture_construct_packed_modifiers(AggregateInput *receiver) {
    static_assert(offsetof(AggregateEvent, control) == offsetof(AggregateEvent, alt) + 1);
    AggregateEvent event{};
    std::memcpy(&event.alt, &receiver->alt, 2);
    std::memcpy(&event.shift, &receiver->shift, 1);
    event.source = receiver;
    fixture_dispatch_packed_modifiers(receiver, event);
}

extern "C" __attribute__((noinline)) void fixture_construct_enter(ConstructorGui *receiver, unsigned count) {
    auto *previous = static_cast<PreviousWidget *>(receiver->previous);
    for (unsigned index = 0; index < count; ++index)
        fixture_shift();
    auto event = createFixtureEvent(receiver, 47);
    event.previous = previous;
    fixture_dispatch_enter(receiver, event);
}

int main() {
    unsigned receiver = 0;
    fixture_construct_down(&receiver);
    fixture_construct_up(&receiver);
    fixture_construct_modifiers(&receiver);
    for (unsigned flags = 0; flags < 8; ++flags) {
        AggregateInput input{};
        input.alt = flags & 1;
        input.control = flags >> 1 & 1;
        input.shift = flags >> 2 & 1;
        fixture_construct_packed_modifiers(&input);
    }
    ConstructorGui gui{};
    fixture_construct_enter(&gui, 3);
    PreviousWidget previous{};
    gui.previous = &previous;
    fixture_construct_enter(&gui, 3);
    return fixture_dispatch_count == 13 ? 0 : 1;
}
