#pragma once
#include "bridge.h"
#include <algorithm>
#include <stdexcept>
#include <string_view>
#include <vector>

template <class T> FmObservationBuffer observationBuffer(const std::vector<T> &values) noexcept {
    return {values.empty() ? 0 : reinterpret_cast<uintptr_t>(values.data()), values.size() * sizeof(T)};
}

inline std::string_view observationText(const FmObservationBuffer &value) {
    return value.size ? std::string_view(reinterpret_cast<const char *>(value.address), value.size)
                      : std::string_view{};
}

// Immutable owned copies until the next admitted command. Borrowed game objects never enter this storage.
struct ObservationStorage {
    std::vector<FmNode> nodes;
    std::vector<FmOption> options;
    std::vector<FmControl> controls;
    std::vector<FmChatRecord> chatRecords;
    std::vector<char> worldJson;
    // Moving an inner vector preserves the byte allocation referenced by its wire descriptor.
    std::vector<std::vector<char>> strings;

    FmObservationBuffer copyText(const char *data, size_t length, bool &truncated) {
        if (!data)
            throw std::runtime_error("Observation string data is absent");
        size_t count = std::min(length, size_t(FM_OBSERVATION_TEXT_BYTES));
        truncated = count < length;
        if (truncated)
            while (count && (static_cast<unsigned char>(data[count]) & 0xc0) == 0x80)
                --count;
        return observationBuffer(strings.emplace_back(data, data + count));
    }

    void publish(FmResult &result) const noexcept {
        result.nodes = result.count ? observationBuffer(nodes) : FmObservationBuffer{};
        result.options = result.optionCount ? observationBuffer(options) : FmObservationBuffer{};
        result.controls = result.controlCount ? observationBuffer(controls) : FmObservationBuffer{};
        result.chat.records = result.chat.count ? observationBuffer(chatRecords) : FmObservationBuffer{};
        result.worldJson = result.worldSize ? observationBuffer(worldJson) : FmObservationBuffer{};
    }
};
