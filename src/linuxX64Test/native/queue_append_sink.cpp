#include "queue_append_fixture.h"

extern "C" void fixture_packet_prepare(Packet &packet) {
    unsigned value = 7;
    for (auto &word : packet.values)
        word = value++;
}

extern "C" void fixture_packet_slow(PacketQueue *queue, const Packet &packet) {
    *queue->cursor = packet;
    ++queue->cursor;
}
