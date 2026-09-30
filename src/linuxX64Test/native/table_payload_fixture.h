#pragma once

#include <cstddef>
#include <cstdint>

struct TablePacket {
    std::uint8_t padding[FIXTURE_PADDING];
    std::uint8_t button;
    std::uint32_t type;
};

struct TablePayload {
    std::uint32_t type;
    std::uint32_t unused;
    double time;
    std::uint32_t code;
    std::uint8_t padding[FIXTURE_PADDING];
};

struct TableQueue {
    TablePayload *data;
    unsigned size;
    unsigned capacity;
};

extern "C" void fixture_table_grow(TableQueue *queue);
