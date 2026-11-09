#pragma once
#include <stdint.h>

typedef struct FmUiChildRange {
    uint32_t begin, end;
} FmUiChildRange;

#ifdef __cplusplus
#include "widget_selection.h"
#include <cstdint>
#include <unordered_set>

namespace fm::ui {

/** Borrowed identities exist only during this synchronous, postorder traversal. */
template <class Read, class Visit, class Canceled>
void walk(uintptr_t root, uint32_t objectSize, const FmUiChildRange *ranges, size_t rangeCount,
          Read read, Visit visit, Canceled canceled) {
    require(root && objectSize >= sizeof(uintptr_t) && ranges && rangeCount, "Invalid UI traversal layout");
    for (size_t index = 0; index < rangeCount; ++index) {
        const auto &range = ranges[index];
        require(range.begin <= objectSize - sizeof(uintptr_t) && range.end <= objectSize - sizeof(uintptr_t) &&
                    range.begin != range.end, "Invalid UI child range layout");
    }
    struct Frame {
        uintptr_t object, next = 0, end = 0;
        size_t range = 0;
        bool loaded = false;
    };
    std::vector<Frame> pending;
    std::unordered_set<uintptr_t> ancestors;
    auto enter = [&](uintptr_t object) {
        require(object && object <= uintptr_t(INTPTR_MAX) - objectSize, "Invalid UI widget address");
        require(pending.size() < INT_MAX && ancestors.insert(object).second, "Cyclic UI child hierarchy");
        pending.push_back({object});
    };
    enter(root);
    while (!pending.empty()) {
        if (canceled())
            throw Error(Failure::canceled, "UI traversal canceled");
        auto &frame = pending.back();
        if (frame.range == rangeCount) {
            visit(frame.object, static_cast<int>(pending.size() - 1));
            ancestors.erase(frame.object);
            pending.pop_back();
            continue;
        }
        if (!frame.loaded) {
            const auto &range = ranges[frame.range];
            require(read(frame.object + range.begin, frame.next) && read(frame.object + range.end, frame.end) &&
                        frame.next <= frame.end && (frame.end - frame.next) % sizeof(uintptr_t) == 0 &&
                        (!frame.next ? !frame.end : frame.next % alignof(uintptr_t) == 0),
                    "Unreadable or invalid UI child range");
            frame.loaded = true;
        }
        if (frame.next == frame.end) {
            frame.loaded = false;
            ++frame.range;
            continue;
        }
        uintptr_t child = 0;
        require(read(frame.next, child), "Unreadable UI child pointer");
        frame.next += sizeof(uintptr_t);
        enter(child);
    }
}

} // namespace fm::ui
#endif
