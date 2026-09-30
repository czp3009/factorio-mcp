#include "input_state_fixture.h"
#include <cstdlib>
#include <cstring>

__attribute__((noinline)) void operator delete(void *pointer, std::size_t) noexcept {
    std::free(pointer);
}

extern "C" {
FixtureStateContext *fixture_state_context;
extern const std::uint64_t fixture_state_size = sizeof(FixtureState);
extern const std::uint64_t fixture_state_context_size = sizeof(FixtureStateContext);
extern const std::uint64_t fixture_state_offset = offsetof(FixtureStateContext, state);
extern const std::uint64_t fixture_state_mask_offset = offsetof(FixtureState, mask);
extern const std::uint64_t fixture_event_extent = sizeof(FixtureStateEvent);
extern const std::uint64_t fixture_event_type = offsetof(FixtureStateEvent, type);
extern const std::uint64_t fixture_event_time = offsetof(FixtureStateEvent, time);
extern const std::uint64_t fixture_event_code = offsetof(FixtureStateEvent, code);
}

extern "C" __attribute__((noinline)) void fixture_state_update(FixtureState *state, const FixtureStateEvent *event) {
    switch (event->type) {
        case 0:
            state->mask = 0;
            break;
        case 1:
            state->mask = event->code;
            break;
        case 2:
            state->keys.clear();
            break;
        case 3:
            state->mask |= 1u << ((static_cast<std::uint8_t>(event->code) - 1u) & 31);
            state->mask ^= fixture_observe_event(event);
            break;
        case 4:
            state->mask += event->code;
            break;
        case 5:
            state->mask &= ~(1u << ((event->code - 1u) & 31));
            break;
        case 6:
            state->buttons.clear();
            break;
        case 7:
            state->mask = fixture_state_mask(state) + event->code;
            break;
        case 8:
            state->mask ^= event->code;
            break;
        default:
            break;
    }
}

extern "C" __attribute__((noinline)) void fixture_delete_state(FixtureState *state) {
    state->~FixtureState();
    ::operator delete(state, sizeof(FixtureState));
}

extern "C" __attribute__((noinline)) void fixture_state_post(FixtureState *state, const FixtureStateEvent *event) {
    if (event->type == 1)
        state->mask = fixture_state_mask(state) + event->code;
    else if (event->type == 8)
        state->mask = event->code;
}

extern "C" void fixture_event_copy_slow(FixtureStateEvent *, const FixtureStateEvent *);

extern "C" __attribute__((noinline)) void fixture_event_copy(FixtureStateEvent *target, const FixtureStateEvent *source) {
    target->type = source->type;
    target->time = source->time;
    switch (source->type) {
        case 0:
            target->code += source->code;
            break;
        case 1:
            fixture_event_copy_slow(target, source);
            break;
        case 2:
            target->code ^= source->code;
            break;
        case 3:
        case 5:
            target->code = source->code;
            std::memcpy(target->payload, source->payload, sizeof(target->payload));
            break;
        case 4:
            target->code -= source->code;
            break;
        case 6:
            target->time += source->time;
            break;
        default:
            target->code = 0;
            break;
    }
}

extern "C" __attribute__((noinline)) unsigned fixture_read_state() {
    return fixture_state_mask(fixture_state_context->state) + 1;
}

int main() {
    for (unsigned count = 0; count < 8; ++count) {
        auto *state = new FixtureState{};
        state->keys.resize(count);
        state->buttons.resize(7 - count);
        state->mask = count;
        FixtureStateContext context{};
        context.state = state;
        fixture_state_context = &context;
        if (fixture_read_state() != count + 1) return 1;
        for (unsigned code = 1; code <= 255; ++code) {
            FixtureStateEvent event{};
            event.code = code;
            const auto original = state->mask;
            const auto bit = 1u << ((code - 1) & 31);
            event.type = 3;
            event.time = 1.25;
            for (unsigned byte = 0; byte < sizeof(event.payload); ++byte)
                event.payload[byte] = static_cast<std::uint8_t>(code + byte);
            FixtureStateEvent copied{};
            fixture_event_copy(&copied, &event);
            if (copied.type != event.type || copied.time != event.time || copied.code != event.code) return 6;
            if (std::memcmp(copied.payload, event.payload, sizeof(event.payload))) return 8;
            fixture_state_update(state, &event);
            if (state->mask != (original | bit)) return 2;
            fixture_state_post(state, &event);
            if (state->mask != (original | bit)) return 4;
            event.type = 5;
            fixture_event_copy(&copied, &event);
            if (copied.type != event.type || copied.time != event.time || copied.code != event.code) return 7;
            if (std::memcmp(copied.payload, event.payload, sizeof(event.payload))) return 9;
            fixture_state_update(state, &event);
            if (state->mask != (original & ~bit)) return 3;
            fixture_state_post(state, &event);
            if (state->mask != (original & ~bit)) return 5;
        }
        fixture_delete_state(state);
        fixture_state_context = nullptr;
    }
    return 0;
}
