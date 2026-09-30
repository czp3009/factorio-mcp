#include <cstddef>
#include <cstdint>

struct InlinePacket {
    unsigned char padding[FIXTURE_PADDING + 16];
    std::uint16_t button;
    bool controlled;
    bool alternate;
    bool shifted;
    std::uint64_t stamp;

    __attribute__((always_inline)) std::uint16_t getButton() const { return button; }
    __attribute__((always_inline)) bool control() const { return controlled; }
    __attribute__((always_inline)) std::uint64_t getStamp() const { return stamp; }
};

struct ConvertedPacket {
    std::uint64_t stamp;
    unsigned char padding[FIXTURE_PADDING];
    bool shifted;
    std::uint16_t button;
    bool controlled;
    unsigned char separator[3];
    bool alternate;
};

__attribute__((always_inline)) inline InlinePacket dequeueMouseInput(const InlinePacket *packet) {
    return *packet;
}

extern "C" {
extern const std::uint64_t fixture_inline_extent = sizeof(InlinePacket);
extern const std::uint64_t fixture_inline_button = offsetof(InlinePacket, button);
extern const std::uint64_t fixture_inline_control = offsetof(InlinePacket, controlled);
extern const std::uint64_t fixture_inline_stamp = offsetof(InlinePacket, stamp);
extern const std::uint64_t fixture_inline_alt = offsetof(InlinePacket, alternate);
extern const std::uint64_t fixture_inline_shift = offsetof(InlinePacket, shifted);
extern const std::uint64_t fixture_converted_extent = sizeof(ConvertedPacket);
extern const std::uint64_t fixture_converted_stamp = offsetof(ConvertedPacket, stamp);
extern const std::uint64_t fixture_converted_button = offsetof(ConvertedPacket, button);
extern const std::uint64_t fixture_converted_control = offsetof(ConvertedPacket, controlled);
extern const std::uint64_t fixture_converted_alt = offsetof(ConvertedPacket, alternate);
extern const std::uint64_t fixture_converted_shift = offsetof(ConvertedPacket, shifted);

__attribute__((noinline)) unsigned fixture_read_button(void *, const InlinePacket *packet) {
    return packet->getButton();
}

__attribute__((noinline)) bool fixture_read_control(void *, const InlinePacket *packet) {
    return packet->control();
}

__attribute__((noinline)) std::uint64_t fixture_read_stack(void *, InlinePacket packet) {
    auto stamp = packet.getStamp();
    asm volatile("" : "+r"(stamp));
    return packet.getButton() + packet.control() + stamp;
}

__attribute__((noinline)) std::uint64_t fixture_read_stack_folded(void *, InlinePacket packet) {
    return packet.getButton() + packet.control() + packet.getStamp();
}

__attribute__((noinline)) std::uint64_t fixture_copy_frame(void *, const InlinePacket *packet) {
    auto local = dequeueMouseInput(packet);
    asm volatile("" : "+m"(local));
    return fixture_read_stack(nullptr, local);
}

__attribute__((noinline)) std::uint64_t fixture_read_converted(void *, const ConvertedPacket *packet) {
    return packet->stamp + packet->button + packet->controlled + packet->alternate * 2 + packet->shifted * 4;
}

__attribute__((noinline)) std::uint64_t fixture_read_converted_stack(void *, ConvertedPacket packet) {
    return fixture_read_converted(nullptr, &packet);
}

__attribute__((noinline)) std::uint64_t fixture_convert_frame(void *, const InlinePacket *packet) {
    auto local = dequeueMouseInput(packet);
    asm volatile("" : "+m"(local));
    ConvertedPacket event{};
    event.stamp = local.stamp;
    event.button = local.button;
    event.controlled = local.controlled;
    event.alternate = local.alternate;
    event.shifted = local.shifted;
    return fixture_read_converted(nullptr, &event);
}

__attribute__((noinline)) std::uint64_t fixture_convert_stack(void *, const InlinePacket *packet) {
    auto local = dequeueMouseInput(packet);
    asm volatile("" : "+m"(local));
    ConvertedPacket event{};
    event.stamp = local.stamp;
    event.button = local.button;
    event.controlled = local.controlled;
    event.alternate = local.alternate;
    event.shifted = local.shifted;
    return fixture_read_converted_stack(nullptr, event);
}
}

int main() {
    InlinePacket packet{};
    packet.button = 321;
    packet.controlled = true;
    packet.stamp = 123456;
    for (unsigned flags = 0; flags != 8; ++flags) {
        packet.controlled = flags & 1;
        packet.alternate = flags & 2;
        packet.shifted = flags & 4;
        if (fixture_convert_frame(nullptr, &packet) != packet.stamp + packet.button + flags)
            return 2;
        if (fixture_convert_stack(nullptr, &packet) != fixture_convert_frame(nullptr, &packet))
            return 3;
    }
    return fixture_read_button(nullptr, &packet) == packet.button && fixture_read_control(nullptr, &packet) &&
           fixture_read_stack(nullptr, packet) == packet.button + packet.controlled + packet.stamp &&
           fixture_copy_frame(nullptr, &packet) == fixture_read_stack(nullptr, packet) &&
           fixture_read_stack_folded(nullptr, packet) == fixture_read_stack(nullptr, packet) ? 0 : 1;
}
