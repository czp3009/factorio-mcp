#pragma once
#include <cstddef>
#include <cstdint>

struct AggregateEvent {
    int x;
    int y;
    unsigned char padding[FIXTURE_PADDING];
    std::uint16_t mask;
    std::uint32_t kind;
    double timestamp;
    bool alt;
    bool control;
    bool shift;
    void *source;
    void *previous;
};

struct AggregateInput {
    unsigned char padding[FIXTURE_PADDING];
    unsigned char alt;
    unsigned char control;
    unsigned char shift;
};

struct PreviousPrefix { std::uintptr_t padding[FIXTURE_PADDING]; };
struct PreviousBase { std::uintptr_t value; };
struct PreviousWidget : PreviousPrefix, PreviousBase {};
struct ConstructorGui {
    unsigned char padding[FIXTURE_PADDING];
    PreviousBase *previous;
};

extern "C" void fixture_dispatch_down(void *receiver, const AggregateEvent &event);
extern "C" void fixture_dispatch_up(void *receiver, const AggregateEvent &event);
extern "C" void fixture_dispatch_modifiers(void *receiver, const AggregateEvent &event);
extern "C" void fixture_dispatch_packed_modifiers(void *receiver, const AggregateEvent &event);
extern "C" void fixture_dispatch_enter(void *receiver, const AggregateEvent &event);
extern "C" bool fixture_shift();
extern "C" bool fixture_control();
extern "C" unsigned fixture_dispatch_count;
