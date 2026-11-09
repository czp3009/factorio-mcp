#pragma once
#include "text_slice.h"

typedef struct FmChatReadOptions {
    uint64_t tick;
    uint32_t hasOffset, counts[2], limit;
    FmTextSliceRequest textSlice;
} FmChatReadOptions;

#ifdef __cplusplus
#include <map>
#include <numeric>

// Preserve retained-history metadata for watermarks; transfer text only for the requested page.
template <class Record>
void selectChatText(std::vector<Record> &records, const FmChatReadOptions &request,
                    std::vector<std::vector<char>> &strings) {
    if (request.hasOffset > 1 || !request.limit || request.limit > INT_MAX ||
        request.counts[0] > INT_MAX || request.counts[1] > INT_MAX || request.textSlice.source > 3)
        throw std::invalid_argument("Invalid chat selection");
    std::vector<size_t> order(records.size());
    std::iota(order.rbegin(), order.rend(), size_t{0});
    std::stable_sort(order.begin(), order.end(), [&](size_t left, size_t right) {
        const auto &a = records[left], &b = records[right];
        return a.tick == b.tick ? a.stream < b.stream : a.tick < b.tick;
    });
    std::map<std::pair<uint64_t, uint32_t>, uint32_t> positions;
    std::vector<size_t> candidates;
    for (auto index : order) {
        const auto &record = records[index];
        if (record.stream > 1) throw std::invalid_argument("Invalid chat storage");
        const auto position = positions[{record.tick, record.stream}]++;
        if (!request.hasOffset || record.tick > request.tick ||
            (record.tick == request.tick && position >= request.counts[record.stream]))
            candidates.push_back(index);
    }
    if (!request.hasOffset && candidates.size() > request.limit)
        candidates.erase(candidates.begin(), candidates.end() - request.limit);
    if (candidates.size() > request.limit) candidates.resize(request.limit);
    std::vector<bool> selected(records.size());
    for (auto index : candidates) selected[index] = true;
    for (size_t index = 0; index < records.size(); ++index) {
        auto &record = records[index];
        record.textIncluded = selected[index];
        record.textRange = {};
        const auto source = request.textSlice.source;
        if (selected[index] && source) {
            auto &text = source == 1 ? record.text : source == 2 ? record.raw : record.playerName;
            record.textRange = sliceObservation(text, request.textSlice.offset, request.textSlice.limit, strings);
        }
        if (!selected[index] || (source && source != 1)) record.text = {};
        if (!selected[index] || (source && source != 2)) record.raw = {};
        if (!selected[index] || (source && source != 3)) record.playerName = {};
    }
}
#endif
