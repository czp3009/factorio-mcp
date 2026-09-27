#include "input_transfer.h"
#include <cassert>
#include <cstddef>

struct Segment {
    uint16_t type;
    uint32_t index, total;
};

struct Batch {
    Segment *begin, *end;
};

struct Queue {
    Batch **map;
    uint64_t mapSize, first, count;
};

struct Listener {
    Queue queue;
};

struct Synchronizer {
    Listener *listener;
};

struct Client {
    Synchronizer *synchronizer;
};

struct FixtureGlobal {
    Client *client;
};

int main() {
    Symbols symbols{};
    auto &layout = symbols.inputTransfer;
    layout.supported = 1;
    layout.client = offsetof(FixtureGlobal, client);
    layout.synchronizer = offsetof(Client, synchronizer);
    layout.listener = offsetof(Synchronizer, listener);
    layout.queue = offsetof(Listener, queue);
    layout.map = offsetof(Queue, map);
    layout.mapSize = offsetof(Queue, mapSize);
    layout.first = offsetof(Queue, first);
    layout.count = offsetof(Queue, count);
    layout.blockSize = 2;
    layout.vectorSize = sizeof(Batch);
    layout.vectorBegin = offsetof(Batch, begin);
    layout.vectorEnd = offsetof(Batch, end);
    layout.segmentSize = sizeof(Segment);
    layout.actionType = offsetof(Segment, type);
    layout.segmentIndex = offsetof(Segment, index);
    layout.totalSegments = offsetof(Segment, total);
    layout.importAction = 81; // Synthetic fixture identity, not a game enum value.
    Segment segments[] = {{42, 1, 2}, {81, 7, 10}, {81, 8, 10}};
    Batch block[2] = {{nullptr, nullptr}, {segments, segments + 3}};
    Batch *map[] = {nullptr, block};
    Listener listener{{map, 2, 7, 1}};
    Synchronizer synchronizer{&listener};
    Client client{&synchronizer};
    FixtureGlobal global{&client};
    FmInputTransfer result{};
    readInputTransfer(symbols, &global, result);
    assert(result.available && result.clientPresent && result.importPresent);
    assert(result.queuedBatches == 1 && result.segmentIndex == 7 && result.totalSegments == 10);
    segments[1].total = 0;
    readInputTransfer(symbols, &global, result);
    assert(!result.available && !result.importPresent && result.reason[0]);
    listener.queue.count = 0;
    readInputTransfer(symbols, &global, result);
    assert(result.available && !result.importPresent && !result.queuedBatches);
    listener.queue.count = 65537;
    readInputTransfer(symbols, &global, result);
    assert(!result.available);
    global.client = nullptr;
    readInputTransfer(symbols, &global, result);
    assert(result.available && !result.clientPresent && !result.importPresent);
    layout.supported = 0;
    readInputTransfer(symbols, &global, result);
    assert(!result.available);
}
