#include <cassert>
#include <cstddef>
#include <cstdint>

extern "C" bool fixture_accept(const void* value);
constexpr unsigned mouseKind = FIXTURE_PADDING == 1 ? 2 : 4;

struct Event {
    unsigned kind;
    unsigned other;
    std::uint64_t padding[FIXTURE_PADDING];
    unsigned button;
};

struct Value {
    std::uint64_t padding[FIXTURE_PADDING];
    std::uint8_t kind;
    std::uint64_t more[FIXTURE_PADDING];
    unsigned code;

    __attribute__((noinline)) bool matches(const Event& event) const {
        switch (kind) {
            case mouseKind:
                if (event.kind != 17 || code != event.button) return false;
                return fixture_accept(this);
            case 1: return event.other == code + 11;
            case 3: return event.other == code + 22;
            case 5: return event.other == code + 33;
            case 6: return event.other == code + 44;
            default: return false;
        }
    }
};

extern "C" {
extern const std::size_t fixture_size = sizeof(Value);
extern const std::size_t fixture_type = offsetof(Value, kind);
extern const std::size_t fixture_code = offsetof(Value, code);
extern const std::size_t fixture_kind = mouseKind;
extern const std::size_t fixture_event_size = sizeof(Event);
extern const std::size_t fixture_event_code = offsetof(Event, button);
}

int main() {
    Value value{};
    Event event{};
    value.kind = mouseKind;
    event.kind = 17;
    for (unsigned code = 0; code < 64; ++code) {
        value.code = code;
        for (unsigned button = 0; button < 64; ++button) {
            event.button = button;
            assert(value.matches(event) == (code == button));
        }
    }
    event.kind = 18;
    event.button = value.code;
    assert(!value.matches(event));
}
