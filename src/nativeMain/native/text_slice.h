#pragma once
#include <stdint.h>

typedef struct FmTextRange {
    uint64_t total;
    uint32_t available, offset, hasMore, nextOffset;
} FmTextRange;

typedef struct FmTextSliceRequest {
    uint32_t source, offset, limit;
} FmTextSliceRequest;

#ifdef __cplusplus
#include <algorithm>
#include <climits>
#include <stdexcept>
#include <vector>

// Slice owned UTF-8 observations before exposing their byte ranges to the MCP process.
template <class Buffer>
FmTextRange sliceObservation(Buffer &text, uint32_t offset, uint32_t limit,
                             std::vector<std::vector<char>> &strings) {
    if (offset > INT_MAX || !limit || limit > INT_MAX || text.size > INT_MAX || (!text.address && text.size))
        throw std::invalid_argument("Invalid native text slice");
    const auto total = static_cast<size_t>(text.size);
    const auto *data = reinterpret_cast<const char *>(text.address);
    const auto start = std::min<size_t>(offset, total);
    auto continuation = [&](size_t index) { return (static_cast<unsigned char>(data[index]) & 0xc0) == 0x80; };
    if (start < total && continuation(start))
        throw std::invalid_argument("String offset is inside a UTF-8 character");
    auto end = std::min(total, start + limit);
    if (end < total)
        while (end > start && continuation(end)) --end;
    if (end == start && start != total)
        throw std::invalid_argument("String limit cannot contain the next UTF-8 character");
    std::vector<char> value;
    if (end > start) value.assign(data + start, data + end);
    auto &copy = strings.emplace_back(std::move(value));
    text = {copy.empty() ? 0 : reinterpret_cast<uintptr_t>(copy.data()), copy.size()};
    return {total, 1, offset, end < total, static_cast<uint32_t>(end)};
}

template <class Node, class Options>
FmTextRange sliceUiText(Node &node, Options &options, const FmTextSliceRequest &request,
                        bool textAvailable, bool optionsAvailable, std::vector<std::vector<char>> &strings) {
    decltype(node.text) *text = nullptr;
    if (request.source == 1 && textAvailable) text = &node.text;
    else if (request.source == 2 && node.tooltipStatus == 3) text = &node.tooltipTitle;
    else if (request.source == 3 && node.tooltipStatus == 3) text = &node.tooltipText;
    else if (request.source == 4 && optionsAvailable) {
        if (node.optionOffset >= node.optionTotal || node.optionCount != 1 || node.optionFirst >= options.size())
            throw std::invalid_argument("Option index is out of range");
        text = &options[node.optionFirst].text;
    } else if (request.source > 4) throw std::invalid_argument("Unknown UI text source");
    const auto result = text ? sliceObservation(*text, request.offset, request.limit, strings) : FmTextRange{};
    if (request.source == 2) node.tooltipText = {};
    if (request.source == 3) node.tooltipTitle = {};
    return result;
}
#endif
