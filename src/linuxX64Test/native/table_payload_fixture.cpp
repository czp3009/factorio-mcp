#include "table_payload_fixture.h"

extern "C" {
extern const std::uint32_t fixture_table_values[] = {31, 12, 93, 44, 78, 16, 53, 62, 8};
extern const std::uint64_t fixture_packet_extent = sizeof(TablePacket);
extern const std::uint64_t fixture_packet_type = offsetof(TablePacket, type);
extern const std::uint64_t fixture_payload_extent = sizeof(TablePayload);
extern const std::uint64_t fixture_payload_type = offsetof(TablePayload, type);
extern const std::uint64_t fixture_payload_time = offsetof(TablePayload, time);
extern const std::uint64_t fixture_payload_code = offsetof(TablePayload, code);
}

extern "C" __attribute__((noinline)) void fixture_table_append(TableQueue *queue, const TablePacket *packet, double time) {
    const auto index = static_cast<std::uint8_t>(packet->button - FIXTURE_PADDING);
    if (index > 8) return;
    const auto code = fixture_table_values[index];
    const auto type = 7u + (packet->type == FIXTURE_PADDING);
    if (queue->size == queue->capacity) fixture_table_grow(queue);
    auto &output = queue->data[queue->size++];
    output.type = type;
    output.time = time;
    output.code = code;
}

int main() {
    for (unsigned button = 0; button < 256; ++button) {
        for (unsigned type = 0; type < 32; ++type) {
            TablePacket packet{};
            packet.button = static_cast<std::uint8_t>(button);
            packet.type = type;
            for (unsigned capacity = 0; capacity <= 2; capacity += 2) {
                TablePayload data[2]{};
                TableQueue queue{data, 0, capacity};
                fixture_table_append(&queue, &packet, 1.25);
                const auto index = static_cast<std::uint8_t>(button - FIXTURE_PADDING);
                if (queue.size != (index <= 8 ? 1u : 0u)) return 1;
                if (queue.size && (data[0].code != fixture_table_values[index] || data[0].time != 1.25 ||
                    data[0].type != 7u + (type == FIXTURE_PADDING))) return 2;
            }
        }
    }
    return 0;
}
