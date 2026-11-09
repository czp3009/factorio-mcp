#pragma once
#include <stdint.h>

// Validated from the selected executable's debug metadata or semantic accessor proofs.
typedef struct FmGuiRegistryLayout {
    uint32_t supported, playerSize, playerGui, guiSize, map, mapHead, mapCount;
    uint32_t nodeSize, left, right, parent, nil, key, value;
    uint32_t elementSize, elementWidget, elementGui, elementIndex;
    // MSVC uses a separately allocated sentinel with a nil byte. libstdc++ embeds a header.
    uint32_t embeddedHead;
} FmGuiRegistryLayout;

#ifdef __cplusplus
#include <climits>
#include <limits>
#include <stdexcept>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace fm::ui {
inline size_t guiMatchSize(size_t count, size_t steps) {
    if (!steps || steps > INT_MAX || count > size_t(INT_MAX) / steps)
        throw std::runtime_error("Script GUI predicates exceed byte-array representation");
    return count * steps;
}

inline bool guiMember(uint32_t offset, size_t width, uint32_t size) {
    return width <= size && offset <= size - width;
}

inline bool validGuiRegistry(const FmGuiRegistryLayout &layout) {
    return layout.supported == 1 && layout.embeddedHead <= 1 &&
        guiMember(layout.playerGui, 8, layout.playerSize) && layout.map <= layout.guiSize &&
        guiMember(layout.mapHead, 8, layout.guiSize - layout.map) &&
        guiMember(layout.mapCount, 8, layout.guiSize - layout.map) &&
        guiMember(layout.left, 8, layout.nodeSize) && guiMember(layout.right, 8, layout.nodeSize) &&
        guiMember(layout.parent, 8, layout.nodeSize) &&
        (layout.embeddedHead || guiMember(layout.nil, 1, layout.nodeSize)) &&
        guiMember(layout.key, 8, layout.nodeSize) && guiMember(layout.value, 8, layout.nodeSize) &&
        guiMember(layout.elementWidget, 8, layout.elementSize) &&
        guiMember(layout.elementGui, 8, layout.elementSize) &&
        guiMember(layout.elementIndex, 4, layout.elementSize);
}

// A complete existing registry is joined by exact identity, never by position or display text.
template <class Read, class Canceled>
std::unordered_map<uintptr_t, uint32_t> guiRegistry(uintptr_t player, const FmGuiRegistryLayout &layout,
                                                 Read read, Canceled canceled) {
    auto require = [](bool value, const char *message) {
        if (!value)
            throw std::runtime_error(message);
    };
    require(validGuiRegistry(layout), "Invalid script GUI registry metadata");
    auto member = [&](uintptr_t object, uint32_t offset, auto &value) {
        return object && object <= INTPTR_MAX - offset - sizeof(value) && read(object + offset, value);
    };
    uintptr_t gui = 0, head = 0, root = 0;
    uint64_t count = 0;
    require(member(player, layout.playerGui, gui), "Cannot read local player's script GUI");
    if (!gui)
        return {};
    require(gui <= INTPTR_MAX - layout.guiSize, "Invalid script GUI address");
    const uintptr_t map = gui + layout.map;
    if (layout.embeddedHead)
        head = map + layout.mapHead;
    else
        require(member(map, layout.mapHead, head), "Cannot read script GUI registry header");
    require(member(map, layout.mapCount, count) && count <= INT_MAX &&
                member(head, layout.parent, root), "Invalid script GUI registry size or root");
    std::unordered_map<uintptr_t, uint32_t> result;
    std::unordered_set<uintptr_t> visited;
    std::unordered_set<uint32_t> indices;
    std::vector<uintptr_t> pending;
    if (root && root != head)
        pending.push_back(root);
    while (!pending.empty()) {
        require(!canceled(), "Script GUI registry observation canceled");
        const auto node = pending.back();
        pending.pop_back();
        require(node && node != head && node <= INTPTR_MAX - layout.nodeSize &&
                    visited.size() < count && visited.insert(node).second, "Invalid script GUI registry tree");
        uint8_t nil = 0;
        if (!layout.embeddedHead)
            require(member(node, layout.nil, nil) && nil == 0, "Unexpected script GUI registry sentinel");
        uintptr_t left = 0, right = 0, widget = 0, element = 0, owner = 0, elementWidget = 0;
        uint32_t index = 0;
        require(member(node, layout.left, left) && member(node, layout.right, right) &&
                    member(node, layout.key, widget) && member(node, layout.value, element),
                "Cannot read script GUI registry node");
        require(element && element <= INTPTR_MAX - layout.elementSize &&
                    member(element, layout.elementWidget, elementWidget) && elementWidget == widget && widget &&
                    member(element, layout.elementGui, owner) && owner == gui &&
                    member(element, layout.elementIndex, index), "Script GUI registry identity differs");
        require(result.emplace(widget, index).second && indices.insert(index).second,
                "Duplicate script GUI registry identity");
        for (const auto child : {left, right}) {
            if (child && child != head)
                pending.push_back(child);
        }
    }
    require(visited.size() == count, "Incomplete script GUI registry tree");
    return result;
}

inline std::vector<char> guiIndexPayload(uint32_t phase, const std::vector<int64_t> &indices) {
    if ((phase != 1 && phase != 2) || indices.size() > (INT_MAX - 8) / 8)
        throw std::runtime_error("Script GUI payload exceeds byte-array representation");
    std::vector<char> result;
    result.reserve(8 + indices.size() * 8);
    auto word = [&](uint32_t value) {
        for (unsigned shift = 0; shift < 32; shift += 8)
            result.push_back(static_cast<char>((value >> shift) & 255));
    };
    word(phase);
    word(static_cast<uint32_t>(indices.size()));
    for (auto index : indices) {
        if (index < -1 || index > UINT32_MAX)
            throw std::runtime_error("Invalid script GUI element index");
        word(index < 0 ? 0 : static_cast<uint32_t>(index));
        word(index >= 0 ? 1 : 0);
    }
    return result;
}
} // namespace fm::ui
#endif
