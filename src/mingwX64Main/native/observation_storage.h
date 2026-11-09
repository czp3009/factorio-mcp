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
    uint32_t optionOffset = 0, optionLimit = INT32_MAX;
    std::vector<FmNode> nodes;
    std::vector<FmOption> options;
    std::vector<fm::GraphStorage> graphs;
    std::vector<FmControl> controls;
    fm::BindingSelection bindingSelection;
    std::vector<FmChatRecord> chatRecords;
    std::vector<char> worldJson;
    std::vector<char> guiMatches;
    std::vector<unsigned char> framePixels;
    // Moving an inner vector preserves the byte allocation referenced by its wire descriptor.
    std::vector<std::vector<char>> strings;

    FmObservationBuffer copyText(const char *data, size_t length, bool &truncated) {
        if (!data)
            throw std::runtime_error("Observation string data is absent");
        if (length > INT32_MAX)
            throw std::length_error("Observation string exceeds byte-array representation");
        truncated = false;
        return observationBuffer(strings.emplace_back(data, data + length));
    }

    void publish(FmResult &result) const noexcept {
        result.nodes = result.count ? observationBuffer(nodes) : FmObservationBuffer{};
        result.options = result.optionCount ? observationBuffer(options) : FmObservationBuffer{};
        result.controls = result.controlCount ? observationBuffer(controls) : FmObservationBuffer{};
        result.bindingSelection = bindingSelection.wire();
        result.chat.records = result.chat.count ? observationBuffer(chatRecords) : FmObservationBuffer{};
        result.worldJson = result.worldSize ? observationBuffer(worldJson) : FmObservationBuffer{};
        result.framePixels = observationBuffer(framePixels);
    }
};
