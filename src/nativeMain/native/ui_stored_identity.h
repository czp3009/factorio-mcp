#pragma once
#include <stdint.h>

// A typed retained prototype pointer, or an ID into the game's existing typed registry.
typedef struct FmUiStoredPrototypeLayout {
    uint64_t type, registry;
    uint32_t supported, size, field, indirect, first, last, name;
} FmUiStoredPrototypeLayout;

typedef struct FmUiAchievementLayout {
    uint64_t type;
    uint32_t supported, size, state, shownState, inSidebar;
} FmUiAchievementLayout;

typedef struct FmUiAchievement {
    uint32_t available;
    int32_t state, shownState;
    uint32_t inSidebar;
} FmUiAchievement;

#ifdef __cplusplus
#include <cstring>
#include <stdexcept>

namespace fm {
inline bool validStoredPrototypeLayout(const FmUiStoredPrototypeLayout &v) {
    if (!v.supported) return !v.type && !v.registry;
    return v.supported == 1 && v.type && v.size >= 8 && v.size <= 16 * 1024 * 1024 &&
           v.indirect <= 1 && v.field <= v.size && (v.indirect ? 2u : 8u) <= v.size - v.field &&
           (v.indirect ? v.registry && v.first != v.last && v.first <= 65536 && v.last <= 65536 : !v.registry);
}

inline bool validAchievementLayout(const FmUiAchievementLayout &v) {
    if (!v.supported) return !v.type;
    return v.supported == 1 && v.type && v.size >= 8 && v.size <= 16 * 1024 * 1024 &&
           v.state >= 8 && v.state <= v.size - 4 && v.shownState >= 8 && v.shownState <= v.size - 4 &&
           v.state != v.shownState && v.inSidebar >= 8 && v.inSidebar < v.size;
}

template <class Read> uintptr_t storedPrototype(uintptr_t object, const FmUiStoredPrototypeLayout &v, Read read) {
    auto scalar = [&](uintptr_t at, auto &out) {
        if (!read(at, &out, sizeof(out))) throw std::runtime_error("Stored UI prototype reference is unreadable");
    };
    uintptr_t prototype = 0;
    if (!v.indirect) {
        scalar(object + v.field, prototype);
        return prototype;
    }
    uint16_t id;
    scalar(object + v.field, id);
    uintptr_t first, last;
    scalar(v.registry + v.first, first);
    scalar(v.registry + v.last, last);
    if (last < first || last > INTPTR_MAX || (last - first) % sizeof(uintptr_t) ||
        (last - first) / sizeof(uintptr_t) > UINT16_MAX + size_t{1} || id >= (last - first) / sizeof(uintptr_t))
        throw std::runtime_error("Stored UI prototype ID is outside its typed registry");
    scalar(first + size_t(id) * sizeof(uintptr_t), prototype);
    return prototype;
}
} // namespace fm
#endif
