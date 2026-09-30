#include "queue_append_fixture.h"
#include <cstring>

extern "C" {
extern const std::uint64_t fixture_packet_size = sizeof(Packet);
extern const std::uint64_t fixture_owner_size = sizeof(PacketOwner);
extern const std::uint64_t fixture_queue_offset = offsetof(PacketOwner, queue);
extern const std::uint64_t fixture_cursor_offset = offsetof(PacketOwner, queue) + offsetof(PacketQueue, cursor);
extern const std::uint64_t fixture_limit_offset = offsetof(PacketOwner, queue) + offsetof(PacketQueue, limit);
}

extern "C" __attribute__((noinline)) void fixture_packet_append(PacketOwner *owner) {
    Packet packet;
    fixture_packet_prepare(packet);
    if (owner->queue.cursor == owner->queue.limit - 1) {
        fixture_packet_slow(&owner->queue, packet);
    } else {
        *owner->queue.cursor = packet;
        ++owner->queue.cursor;
    }
}

int main() {
    Packet packets[2]{};
    Packet expected;
    fixture_packet_prepare(expected);
    PacketOwner owner{};
    owner.queue.cursor = packets;
    owner.queue.limit = packets + 2;
    fixture_packet_append(&owner);
    fixture_packet_append(&owner);
    return owner.queue.cursor == packets + 2 && std::memcmp(packets, &expected, sizeof(Packet)) == 0 &&
                   std::memcmp(packets + 1, &expected, sizeof(Packet)) == 0 ? 0 : 1;
}
