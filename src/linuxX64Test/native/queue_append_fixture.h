#pragma once
#include <cstddef>
#include <cstdint>

struct Packet {
    std::uint64_t values[(FIXTURE_PADDING + 22) / 8];
};

struct PacketQueue {
    unsigned char padding[FIXTURE_PADDING];
    Packet *cursor;
    Packet *limit;
};

struct PacketOwner {
    std::uint64_t padding[FIXTURE_PADDING];
    PacketQueue queue;
};

extern "C" void fixture_packet_prepare(Packet &packet);
extern "C" void fixture_packet_slow(PacketQueue *queue, const Packet &packet);
