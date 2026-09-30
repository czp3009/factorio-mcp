#include <cassert>
#include <cstddef>
#include <cstdint>

constexpr unsigned control = 1u << ((FIXTURE_PADDING + 1) % 5);
constexpr unsigned shift = 1u << ((FIXTURE_PADDING + 2) % 5);
constexpr unsigned alt = 1u << ((FIXTURE_PADDING + 3) % 5);

extern "C" {
bool fixture_control_down();
bool fixture_shift_down();
volatile unsigned fixture_pressed;
}

__attribute__((always_inline)) inline bool fixture_alt_down() {
    return (fixture_pressed & 4) != 0;
}

struct Binding {
    std::uint64_t padding[FIXTURE_PADDING];
    unsigned char modifiers;

    __attribute__((noinline)) bool matches() const {
        if ((modifiers & control) && !fixture_control_down()) return false;
        if ((modifiers & shift) && !fixture_shift_down()) return false;
        if ((modifiers & alt) && !fixture_alt_down()) return false;
        return true;
    }
};

extern "C" {
extern const std::size_t fixture_binding_size = sizeof(Binding);
extern const std::size_t fixture_modifiers_field = offsetof(Binding, modifiers);
extern const unsigned fixture_modifier_bits[]{control, shift, alt};
}

int main() {
    Binding binding{};
    for (unsigned flags = 0; flags < 256; ++flags) {
        binding.modifiers = flags;
        for (unsigned pressed = 0; pressed < 8; ++pressed) {
            fixture_pressed = pressed;
            const bool expected = (!(flags & control) || (pressed & 1)) &&
                (!(flags & shift) || (pressed & 2)) && (!(flags & alt) || (pressed & 4));
            assert(binding.matches() == expected);
        }
    }
}
