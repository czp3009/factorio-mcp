#pragma once
#include "request_storage.h"

typedef struct FmBindingSelection {
    uint32_t matchedCount, hasMore, presenceSize;
    uint64_t presenceAddress;
} FmBindingSelection;

#ifdef __cplusplus
#include <algorithm>
#include <cstring>
#include <unordered_map>

namespace fm {
struct BindingSelection {
    uint32_t matchedCount = 0, hasMore = 0;
    std::vector<unsigned char> presence;

    FmBindingSelection wire() const {
        return {matchedCount, hasMore, static_cast<uint32_t>(presence.size()),
                presence.empty() ? 0 : reinterpret_cast<uintptr_t>(presence.data())};
    }
};

inline std::vector<uint32_t> bindingUtf16(std::string_view value) {
    std::vector<uint32_t> output;
    for (size_t index = 0; index < value.size();) {
        const auto first = static_cast<unsigned char>(value[index++]);
        uint32_t code = first;
        int remaining = 0;
        if (first >= 0xc2 && first <= 0xdf) { code &= 0x1f; remaining = 1; }
        else if (first >= 0xe0 && first <= 0xef) { code &= 0xf; remaining = 2; }
        else if (first >= 0xf0 && first <= 0xf4) { code &= 7; remaining = 3; }
        else if (first >= 0x80) throw std::invalid_argument("Invalid UTF-8 control ID");
        const int width = remaining;
        while (remaining--) {
            if (index == value.size()) throw std::invalid_argument("Incomplete UTF-8 control ID");
            const auto next = static_cast<unsigned char>(value[index++]);
            if ((next & 0xc0) != 0x80) throw std::invalid_argument("Invalid UTF-8 control ID");
            code = code << 6 | (next & 0x3f);
        }
        if ((width == 1 && code < 0x80) || (width == 2 && code < 0x800) ||
            (width == 3 && code < 0x10000) || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff))
            throw std::invalid_argument("Invalid Unicode control ID");
        if (code <= 0xffff) output.push_back(code);
        else {
            code -= 0x10000;
            output.push_back(0xd800 + (code >> 10));
            output.push_back(0xdc00 + (code & 0x3ff));
        }
    }
    return output;
}

// The common caller supplies Unicode simple-case equivalence sets, independent of OS locales.
template <class Control>
BindingSelection selectBindings(std::vector<Control> &controls, const FmRequestBuffer &request) {
    const auto bytes = requestView(request);
    size_t cursor = 0;
    auto word = [&]() {
        if (cursor > bytes.size() || bytes.size() - cursor < 4)
            throw std::invalid_argument("Truncated control query");
        const auto *data = reinterpret_cast<const unsigned char *>(bytes.data() + cursor);
        cursor += 4;
        return uint32_t(data[0]) | uint32_t(data[1]) << 8 | uint32_t(data[2]) << 16 | uint32_t(data[3]) << 24;
    };
    const auto offset = word(), limit = word(), selected = word(), count = word();
    if (offset > INT_MAX || !limit || limit > INT_MAX || selected > 1 || count > INT_MAX ||
        count > (bytes.size() - cursor) / 4 || (!selected && count))
        throw std::invalid_argument("Invalid control query bounds");
    std::unordered_map<std::string_view, uint32_t> ids;
    for (uint32_t index = 0; index < count; ++index) {
        const auto length = word();
        if (cursor > bytes.size() || length > bytes.size() - cursor)
            throw std::invalid_argument("Invalid control ID query");
        if (!ids.emplace(bytes.substr(cursor, length), index).second)
            throw std::invalid_argument("Duplicate control ID query");
        cursor += length;
    }
    const auto patternSize = word();
    if (patternSize > (bytes.size() - cursor) / 8) throw std::invalid_argument("Invalid control search");
    std::vector<std::vector<uint32_t>> pattern(patternSize);
    for (auto &equivalents : pattern) {
        const auto size = word();
        if (!size || size > (bytes.size() - cursor) / 4) throw std::invalid_argument("Invalid control search cases");
        for (uint32_t index = 0; index < size; ++index) {
            const auto code = word();
            if (code > 0xffff) throw std::invalid_argument("Invalid control search character");
            equivalents.push_back(code);
        }
    }
    if (cursor != bytes.size()) throw std::invalid_argument("Trailing control query bytes");
    BindingSelection result;
    result.presence.resize(count);
    std::vector<Control> matches;
    for (const auto &control : controls) {
        const std::string_view id(control.id);
        const auto found = ids.find(id);
        if (found != ids.end()) result.presence[found->second] = 1;
        if (selected && found == ids.end()) continue;
        const auto characters = bindingUtf16(id);
        bool matched = pattern.empty();
        for (size_t start = 0; !matched && start + pattern.size() <= characters.size(); ++start) {
            matched = true;
            for (size_t index = 0; index < pattern.size(); ++index) {
                const auto &equivalents = pattern[index];
                if (std::find(equivalents.begin(), equivalents.end(), characters[start + index]) == equivalents.end()) {
                    matched = false;
                    break;
                }
            }
        }
        if (matched) matches.push_back(control);
    }
    std::sort(matches.begin(), matches.end(), [](const Control &left, const Control &right) {
        return bindingUtf16(left.id) < bindingUtf16(right.id);
    });
    result.matchedCount = static_cast<uint32_t>(matches.size());
    const auto begin = std::min<size_t>(offset, matches.size());
    const auto end = std::min(matches.size(), begin + limit);
    result.hasMore = end < matches.size();
    controls.assign(matches.begin() + begin, matches.begin() + end);
    return result;
}
} // namespace fm
#endif
