#include "../../nativeMain/native/widget_walk.h"
#include <cassert>
#include <cstddef>
#include <cstring>
#include <utility>

struct Widget {
    uint64_t padding{};
    Widget **first{}, **last{}, **privateFirst{}, **privateLast{};
};

int main() {
    const FmUiChildRange ranges[] = {
        {offsetof(Widget, first), offsetof(Widget, last)},
        {offsetof(Widget, privateFirst), offsetof(Widget, privateLast)},
    };
    auto read = [](uintptr_t address, auto &value) {
        std::memcpy(&value, reinterpret_cast<const void *>(address), sizeof(value));
        return true;
    };
    for (size_t length : {size_t(3), size_t(257), size_t(4097), size_t(65538)}) {
        std::vector<Widget> widgets(length);
        std::vector<Widget *> edges(length);
        for (size_t index = 0; index < length; ++index) {
            edges[index] = &widgets[(index + 1) % length];
            widgets[index].first = &edges[index];
            widgets[index].last = &edges[index] + 1;
        }
        bool cycle = false;
        try {
            fm::ui::walk(reinterpret_cast<uintptr_t>(widgets.data()), sizeof(Widget), ranges, 2, read,
                [](uintptr_t, int) { assert(false); }, [] { return false; });
        } catch (const fm::ui::Error &error) {
            cycle = error.reason == fm::ui::Failure::invalid;
        }
        assert(cycle);
        widgets.back().first = widgets.back().last = nullptr;
        size_t count = 0;
        fm::ui::walk(reinterpret_cast<uintptr_t>(widgets.data()), sizeof(Widget), ranges, 2, read,
            [&](uintptr_t widget, int depth) {
                assert(widget == reinterpret_cast<uintptr_t>(&widgets[length - 1 - count]));
                assert(depth == static_cast<int>(length - 1 - count));
                ++count;
            }, [] { return false; });
        assert(count == length);
    }
    Widget root, shared;
    Widget *first[] = {&shared};
    Widget *second[] = {&shared};
    root.first = first;
    root.last = first + 1;
    root.privateFirst = second;
    root.privateLast = second + 1;
    std::vector<std::pair<uintptr_t, int>> visits;
    fm::ui::walk(reinterpret_cast<uintptr_t>(&root), sizeof(Widget), ranges, 2, read,
        [&](uintptr_t widget, int depth) { visits.emplace_back(widget, depth); }, [] { return false; });
    assert(visits.size() == 3 && visits[0] == visits[1] && visits[2].second == 0);
    bool canceled = false;
    try {
        fm::ui::walk(reinterpret_cast<uintptr_t>(&root), sizeof(Widget), ranges, 2, read,
            [](uintptr_t, int) { assert(false); }, [] { return true; });
    } catch (const fm::ui::Error &error) {
        canceled = error.reason == fm::ui::Failure::canceled;
    }
    assert(canceled);
    bool unreadable = false;
    try {
        fm::ui::walk(reinterpret_cast<uintptr_t>(&root), sizeof(Widget), ranges, 2,
            [](uintptr_t, auto &) { return false; }, [](uintptr_t, int) { assert(false); }, [] { return false; });
    } catch (const fm::ui::Error &error) {
        unreadable = error.reason == fm::ui::Failure::invalid;
    }
    assert(unreadable);
}
