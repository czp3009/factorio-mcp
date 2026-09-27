#include "input_transfer.h"
#include "protocol.h"
#include <cstring>

namespace {
template <class T> T read(const void *object, uint32_t offset) {
    T value;
    memcpy(&value, static_cast<const unsigned char *>(object) + offset, sizeof(value));
    return value;
}
} // namespace

void readInputTransfer(const Symbols &symbols, const void *global, FmInputTransfer &result) {
    result = {};
    try {
        const auto &layout = symbols.inputTransfer;
        require(layout.supported && global, "Input transfer metadata is unavailable");
        const auto *client = read<const void *>(global, layout.client);
        result.clientPresent = client != nullptr;
        if (!client) {
            result.available = 1;
            return;
        }
        const auto *synchronizer = read<const void *>(client, layout.synchronizer);
        require(synchronizer, "Client synchronizer is unavailable");
        const auto *listener = read<const unsigned char *>(synchronizer, layout.listener);
        require(listener, "Network input listener is unavailable");
        const auto *queue = listener + layout.queue;
        result.queuedBatches = read<uint64_t>(queue, layout.count);
        require(result.queuedBatches <= 65536, "Input transfer queue exceeds observation bound");
        if (result.queuedBatches) {
            const auto *map = read<const unsigned char *const *>(queue, layout.map);
            const auto mapSize = read<uint64_t>(queue, layout.mapSize);
            const auto first = read<uint64_t>(queue, layout.first);
            require(map && mapSize && mapSize <= 65536 && (mapSize & (mapSize - 1)) == 0 && layout.blockSize &&
                        layout.vectorSize && layout.segmentSize,
                    "Invalid input transfer queue layout");
            const auto *block = map[(first / layout.blockSize) & (mapSize - 1)];
            require(block, "Input transfer queue block is absent");
            const auto *batch = block + (first % layout.blockSize) * layout.vectorSize;
            const auto begin = read<uintptr_t>(batch, layout.vectorBegin);
            const auto end = read<uintptr_t>(batch, layout.vectorEnd);
            require(end >= begin && (end - begin) % layout.segmentSize == 0 &&
                        (end - begin) / layout.segmentSize <= 4096 && (begin || begin == end),
                    "Input transfer batch exceeds observation bound");
            // The game's cursor overlay searches this same front batch for its first import.
            for (auto current = begin; current < end; current += layout.segmentSize) {
                const auto *segment = reinterpret_cast<const void *>(current);
                if (read<uint16_t>(segment, layout.actionType) != layout.importAction)
                    continue;
                result.segmentIndex = read<uint32_t>(segment, layout.segmentIndex);
                result.totalSegments = read<uint32_t>(segment, layout.totalSegments);
                require(result.totalSegments && result.segmentIndex < result.totalSegments,
                        "Invalid input transfer segment counters");
                result.importPresent = 1;
                break;
            }
        }
        result.available = 1;
    } catch (const std::exception &error) {
        result.available = 0;
        result.importPresent = 0;
        strncpy_s(result.reason, error.what(), _TRUNCATE);
    }
}
