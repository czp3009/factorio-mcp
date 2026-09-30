#include "control_wheel_fixture.h"
#include <cassert>

constexpr unsigned wheelKind = FIXTURE_PADDING == 1 ? 2 : 5;
constexpr unsigned firstCode = FIXTURE_PADDING == 1 ? 1 : 9;

struct Value {
    std::uint64_t padding[FIXTURE_PADDING];
    std::uint8_t kind;
    std::uint64_t more[FIXTURE_PADDING];
    unsigned code;

    __attribute__((noinline)) String save() const {
        switch (kind) {
            case wheelKind: {
                String result = [&]() -> String {
                    switch (code) {
                        case firstCode: return "mouse-wheel-left";
                        case firstCode + 1: return "mouse-wheel-up";
                        case firstCode + 2: return "mouse-wheel-right";
                        case firstCode + 3: return "mouse-wheel-down";
                        default: return "unknown-wheel";
                    }
                }();
                return *fixture_prefix(&result, 0, 0, nullptr, 0);
            }
            case 0: return "zero";
            case 1: return "one";
            case 3: return "three";
            case 4: return "four";
            case 6: return "six";
            case 7: return "seven";
            default: return "unknown-type";
        }
    }
};

extern "C" {
extern const std::size_t fixture_size = sizeof(Value);
extern const std::size_t fixture_type = offsetof(Value, kind);
extern const std::size_t fixture_code = offsetof(Value, code);
extern const std::size_t fixture_kind = wheelKind;
extern const std::size_t fixture_first_code = firstCode;
extern const std::size_t fixture_string_data = offsetof(String, data);
extern const std::size_t fixture_string_length = offsetof(String, length);
extern const std::size_t fixture_string_local = offsetof(String, storage);
extern const std::size_t fixture_string_size = sizeof(String);
}

int main() {
    Value value{};
    value.kind = wheelKind;
    const char* expected[] = {"mouse-wheel-left", "mouse-wheel-up", "mouse-wheel-right", "mouse-wheel-down"};
    for (unsigned index = 0; index < 4; ++index) {
        value.code = firstCode + index;
        const auto result = value.save();
        assert(result.length == std::strlen(expected[index]) && std::strcmp(result.data, expected[index]) == 0);
    }
    value.code = 0xffffffffu;
    assert(std::strcmp(value.save().data, "unknown-wheel") == 0);
    for (unsigned kind = 0; kind < 256; ++kind) {
        value.kind = kind;
        assert(value.save().length != 0);
    }
}
