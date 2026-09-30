#include <cstddef>
#include <cstdint>

struct MaskInput {
    char padding[FIXTURE_PADDING];
    std::uint32_t type;
    std::int32_t code;
};

extern "C" {
extern const std::uint64_t fixture_mask_extent = sizeof(MaskInput);
extern const std::uint64_t fixture_mask_type = offsetof(MaskInput, type);
extern const std::uint64_t fixture_mask_code = offsetof(MaskInput, code);
}

extern "C" __attribute__((noinline)) std::uint16_t fixture_mask(const MaskInput *input) {
    auto mask = 1u << (static_cast<unsigned>(input->code) & 31);
    if (input->code >= FIXTURE_PADDING) mask = 1;
    if (input->type - FIXTURE_PADDING < 0xfffffffeu) mask = 1;
    return static_cast<std::uint16_t>(mask);
}

extern "C" __attribute__((noinline)) std::uint32_t fixture_kind(const MaskInput *input) {
    return 7u + (input->type == FIXTURE_PADDING);
}

extern "C" __attribute__((noinline)) std::int32_t fixture_gate(const MaskInput *input) {
    if (input->type != FIXTURE_PADDING) return 0;
    return input->code;
}

int main() {
    for (unsigned type = 0; type < 64; ++type) {
        for (int code = -35; code < 64; ++code) {
            MaskInput input{};
            input.type = type;
            input.code = code;
            if (fixture_kind(&input) != 7u + (type == FIXTURE_PADDING)) return 2;
            if (fixture_gate(&input) != (type == FIXTURE_PADDING ? code : 0)) return 3;
            const auto expected = code >= FIXTURE_PADDING || type - FIXTURE_PADDING < 0xfffffffeu
                ? 1u : 1u << (static_cast<unsigned>(code) & 31);
            if (fixture_mask(&input) != static_cast<std::uint16_t>(expected)) return 1;
        }
    }
    return 0;
}
