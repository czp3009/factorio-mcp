#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

constexpr unsigned emptyKind = FIXTURE_PADDING == 1 ? 2 : 5;

struct Value {
    std::uint64_t padding[FIXTURE_PADDING];
    std::uint8_t kind;
    std::uint64_t more[FIXTURE_PADDING];
    unsigned code;

    __attribute__((noinline)) std::string save() const {
        switch (kind) {
            case emptyKind: return {};
            case 0: return std::to_string(code);
            case 1: return "mouse-wheel-up";
            case 3: return "mouse-wheel-down";
            case 4: return "mouse-wheel-left";
            case 6: return "mouse-wheel-right";
            case 7: return std::to_string(code + 1);
            default: return "unknown";
        }
    }
};

extern "C" {
extern const std::size_t fixture_size = sizeof(Value);
extern const std::size_t fixture_type = offsetof(Value, kind);
extern const std::size_t fixture_code = offsetof(Value, code);
extern const std::size_t fixture_kind = emptyKind;
// Checked below against this fixture's standard library, never used as game layout constants.
extern const std::size_t fixture_string_data = 0;
extern const std::size_t fixture_string_length = 8;
extern const std::size_t fixture_string_local = 16;
extern const std::size_t fixture_string_size = sizeof(std::string);
}

int main() {
    std::string sample = "native";
    const char* pointer;
    std::size_t length;
    const auto* bytes = reinterpret_cast<const char*>(&sample);
    std::memcpy(&pointer, bytes + fixture_string_data, sizeof(pointer));
    std::memcpy(&length, bytes + fixture_string_length, sizeof(length));
    assert(pointer == sample.data() && pointer == bytes + fixture_string_local && length == sample.size());
    Value value{};
    value.code = 37;
    for (unsigned kind = 0; kind < 256; ++kind) {
        value.kind = kind;
        assert(value.save().empty() == (kind == emptyKind));
    }
}
