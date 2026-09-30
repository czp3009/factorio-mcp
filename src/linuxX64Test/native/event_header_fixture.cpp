#include <cstddef>
#include <cstdint>
#include <cstdlib>

enum class Type : std::uint32_t { First, Second, Third, Fourth };

struct Event {
    unsigned char padding[FIXTURE_PADDING];
    Type type;
    double time;
    std::uint64_t payload[2];
};

struct Queue {
    Event *entries;
    std::uint32_t capacity;
    std::uint32_t head;
    std::uint32_t count;
};

extern "C" {
extern const std::uint64_t fixture_event_size = sizeof(Event);
extern const std::uint64_t fixture_type_offset = offsetof(Event, type);
extern const std::uint64_t fixture_time_offset = offsetof(Event, time);
}

extern "C" __attribute__((noinline)) void fixture_grow(Queue *queue, std::uint32_t capacity) {
    auto *entries = static_cast<Event *>(std::calloc(capacity, sizeof(Event)));
    if (!entries)
        std::abort();
    for (std::uint32_t index = 0; index < queue->count; ++index)
        entries[index] = queue->entries[(queue->head + index) % queue->capacity];
    std::free(queue->entries);
    queue->entries = entries;
    queue->capacity = capacity;
    queue->head = 0;
}

extern "C" __attribute__((noinline)) Event &fixture_event_emplace(Queue *queue, double &time, Type &type) {
    if (queue->count == queue->capacity)
        fixture_grow(queue, queue->capacity ? queue->capacity * 2 : 4);
    auto index = queue->head + queue->count++;
    if (index >= queue->capacity)
        index -= queue->capacity;
    auto &event = queue->entries[index];
    event.type = type;
    event.time = time;
    switch (type) {
    case Type::First:
        event.payload[0] = 1;
        break;
    case Type::Second:
        event.payload[0] = 2;
        break;
    case Type::Third:
        event.payload[0] = 3;
        break;
    case Type::Fourth:
        event.payload[0] = 4;
        break;
    }
    return event;
}

int main() {
    Queue queue{};
    bool valid = true;
    for (unsigned index = 0; index < 12; ++index) {
        double time = index + 0.25;
        auto type = static_cast<Type>(index % 4);
        const auto &event = fixture_event_emplace(&queue, time, type);
        valid = valid && &event == queue.entries + index && event.time == time && event.type == type &&
                event.payload[0] == index % 4 + 1;
    }
    std::free(queue.entries);
    return valid ? 0 : 1;
}
