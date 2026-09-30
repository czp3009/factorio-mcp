#include <cstddef>
#include <cstdint>

struct BytePacket {
    unsigned char padding[FIXTURE_PADDING];
    std::uint8_t code;
};

extern "C" {
extern const std::uint32_t fixture_byte_values[] = {31, 12, 93, 44, 78, 16, 53, 62, 8};
extern const std::uint64_t fixture_byte_offset = offsetof(BytePacket, code);
}

extern "C" __attribute__((noinline)) std::uint32_t fixture_byte_lookup(const BytePacket *packet) {
    const auto index = static_cast<std::uint8_t>(packet->code - FIXTURE_PADDING);
    if (index > 8) return 0xffffffffu;
    return fixture_byte_values[index];
}

int main() {
    for (unsigned byte = 0; byte < 256; ++byte) {
        BytePacket packet{};
        packet.code = static_cast<std::uint8_t>(byte);
        const auto index = static_cast<std::uint8_t>(byte - FIXTURE_PADDING);
        const auto expected = index > 8 ? 0xffffffffu : fixture_byte_values[index];
        if (fixture_byte_lookup(&packet) != expected) return 1;
    }
    return 0;
}
