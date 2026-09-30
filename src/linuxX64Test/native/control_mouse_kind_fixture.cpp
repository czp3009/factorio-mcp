#include <cassert>
#include <cstddef>
#include <cstdint>
constexpr unsigned mouseKind = FIXTURE_PADDING == 1 ? 13 : 29;
constexpr unsigned limit = FIXTURE_PADDING == 1 ? 7 : 11;
struct Value {
    std::uint64_t padding[FIXTURE_PADDING];
    std::uint8_t kind;
    std::uint64_t more[FIXTURE_PADDING];
    unsigned code;
    __attribute__((noinline)) std::uint16_t mouseMask() const {
        if (kind != mouseKind) return 1;
        const unsigned value = code;
        if (value > limit) return 1;
        asm volatile("" ::: "memory");
        return static_cast<std::uint16_t>(1u << value);
    }
};
extern "C" {
extern const std::size_t fixture_size = sizeof(Value);
extern const std::size_t fixture_type = offsetof(Value, kind);
extern const std::size_t fixture_code = offsetof(Value, code);
extern const std::size_t fixture_kind = mouseKind;
}
int main() {
    Value value{};
    for (unsigned kind = 0; kind < 256; ++kind) {
        value.kind = kind;
        for (unsigned code = 0; code < 32; ++code) {
            value.code = code;
            assert(value.mouseMask() == (kind == mouseKind && code <= limit ? (1u << code) : 1));
        }
        value.code = 0xffffffffu;
        assert(value.mouseMask() == 1);
    }
}
