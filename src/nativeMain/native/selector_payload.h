#pragma once
#include "request_storage.h"
#include "widget_selection.h"
#include <cstring>

namespace fm::ui {
inline std::vector<Step> selectorPath(const FmRequestBuffer &buffer, uint32_t expected) {
    const auto bytes = requestView(buffer);
    size_t cursor = 0;
    auto word = [&]() -> uint32_t {
        require(cursor <= bytes.size() && bytes.size() - cursor >= 4, "Truncated selector payload");
        const auto *data = reinterpret_cast<const unsigned char *>(bytes.data() + cursor);
        cursor += 4;
        return uint32_t(data[0]) | uint32_t(data[1]) << 8 | uint32_t(data[2]) << 16 | uint32_t(data[3]) << 24;
    };
    auto text = [&]() -> std::optional<std::string_view> {
        const auto size = word();
        if (size == UINT32_MAX)
            return std::nullopt;
        require(size <= INT_MAX && cursor <= bytes.size() && size <= bytes.size() - cursor,
                "Selector string exceeds payload");
        const auto value = bytes.substr(cursor, size);
        require(value.find('\0') == std::string_view::npos, "Selector string contains NUL");
        cursor += size;
        return value;
    };
    require(expected > 0 && expected <= INT_MAX && word() == expected && expected <= (bytes.size() - cursor) / 36,
            "Invalid selector step count");
    std::vector<Step> path;
    path.reserve(expected);
    for (uint32_t index = 0; index < expected; ++index) {
        Step step;
        const auto child = word(), position = word(), enabled = word(), visible = word(), gui = word();
        require(child <= 1 && position <= INT_MAX && gui <= 1 &&
                    (enabled <= 1 || enabled == UINT32_MAX) && (visible <= 1 || visible == UINT32_MAX),
                "Invalid selector flags");
        step.child = child != 0;
        step.position = position;
        if (enabled != UINT32_MAX)
            step.enabled = enabled != 0;
        if (visible != UINT32_MAX)
            step.visible = visible != 0;
        if (gui)
            step.guiMatch = index;
        step.type = text();
        step.text = text();
        step.prototypeName = text();
        step.prototypeType = text();
        require(!step.type || !step.type->empty(), "Empty native type predicate");
        require(!step.prototypeName || !step.prototypeName->empty(), "Empty prototype predicate");
        require(!step.prototypeType || !step.prototypeType->empty(), "Empty prototype type predicate");
        path.push_back(step);
    }
    require(cursor == bytes.size(), "Selector payload has trailing bytes");
    return path;
}
} // namespace fm::ui
