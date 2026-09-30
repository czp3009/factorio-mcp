#include <algorithm>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstdlib>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

struct KeyValue {
    std::uint8_t held;
    std::uint64_t stamp;
    std::uint8_t blocked;
};
struct KeyRecord {
    std::uint32_t padding[FIXTURE_PADDING];
    std::int32_t key;
    KeyValue value;
};
struct KeyMap {
    std::uint64_t padding[FIXTURE_PADDING];
    KeyRecord *begin;
    KeyRecord *end;
};

extern "C" {
extern const std::uint64_t fixture_key_map_size = sizeof(KeyMap);
extern const std::uint64_t fixture_key_map_begin = offsetof(KeyMap, begin);
extern const std::uint64_t fixture_key_map_end = offsetof(KeyMap, end);
extern const std::uint64_t fixture_key_map_stride = sizeof(KeyRecord);
extern const std::uint64_t fixture_key_map_key = offsetof(KeyRecord, key);
extern const std::uint64_t fixture_key_map_value = offsetof(KeyRecord, value);
extern const std::uint64_t fixture_key_map_value_size = sizeof(KeyValue);
}

extern "C" __attribute__((noinline)) KeyValue *fixture_key_map_lookup(KeyMap *map, std::int32_t key) {
    auto *cursor = map->begin;
    auto *end = map->end;
    const auto bytes = reinterpret_cast<std::uintptr_t>(end) - reinterpret_cast<std::uintptr_t>(cursor);
    if (bytes <= sizeof(KeyRecord) * 10 - 1) {
        while (cursor != end && cursor->key < key)
            ++cursor;
    } else {
        cursor = std::lower_bound(cursor, end, key, [](const KeyRecord &record, std::int32_t value) {
            return record.key < value;
        });
    }
    if (cursor == end || cursor->key > key)
        std::abort();
    return &cursor->value;
}

int main() {
    KeyRecord records[16]{};
    for (int index = 0; index < 16; ++index) {
        records[index].key = index * 3;
        records[index].value.stamp = index;
    }
    for (int count : {2, 16}) {
        KeyMap map{};
        map.begin = records;
        map.end = records + count;
        for (int index = 0; index < count; ++index) {
            auto *value = fixture_key_map_lookup(&map, records[index].key);
            assert(value == &records[index].value && value->stamp == static_cast<unsigned>(index));
        }
    }
}
