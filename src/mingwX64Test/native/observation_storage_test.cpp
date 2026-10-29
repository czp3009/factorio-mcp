#include "observation_storage.h"
#include <cassert>
#include <memory>
#include <string>
#include <type_traits>

int main() {
    ObservationStorage storage;
    static_assert(noexcept(storage = ObservationStorage{}));
    auto result = std::make_unique<FmResult>();
    storage.publish(*result);
    assert(!result->nodes.address && !result->nodes.size && !result->worldJson.address);
    bool truncated = false;
    const auto original = storage.copyText("original", 8, truncated);
    assert(!truncated && observationText(original) == "original");
    for (int index = 0; index < 6000; ++index) {
        auto &node = storage.nodes.emplace_back();
        const auto text = std::to_string(index);
        node.text = storage.copyText(text.data(), text.size(), truncated);
        ++result->count;
    }
    storage.publish(*result);
    assert(observationText(original) == "original");
    assert(result->nodes.size == 6000 * sizeof(FmNode));
    const auto *nodes = reinterpret_cast<const FmNode *>(result->nodes.address);
    for (int index = 0; index < result->count; ++index)
        assert(observationText(nodes[index].text) == std::to_string(index));
    std::string boundary(FM_OBSERVATION_TEXT_BYTES - 1, 'x');
    boundary += "\xe4\xb8\xad";
    const auto clipped = storage.copyText(boundary.data(), boundary.size(), truncated);
    assert(truncated && clipped.size == FM_OBSERVATION_TEXT_BYTES - 1);
    boundary[0] = 'y';
    assert(observationText(clipped).front() == 'x');
    const auto empty = storage.copyText("", 0, truncated);
    assert(!truncated && !empty.address && !empty.size);
    storage.worldJson.assign(17 * 1024 * 1024 + 7, 'j');
    result->worldSize = static_cast<uint32_t>(storage.worldJson.size());
    storage.publish(*result);
    assert(result->worldJson.size == storage.worldJson.size() && observationText(result->worldJson).back() == 'j');
    storage = ObservationStorage{};
    result->count = 0;
    result->worldSize = 0;
    storage.publish(*result);
    assert(!result->nodes.address && !result->nodes.size && !result->worldJson.address && !result->worldJson.size);
}
