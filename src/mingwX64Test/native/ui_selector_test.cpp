#include "ui_selector.h"
#include <cassert>
#include <cstring>
#include <memory>
#include <stdexcept>

static void node(FmResult &result, int index, int depth, const char *type, const char *text = "") {
    auto &value = result.nodes[index];
    value = {};
    value.depth = depth;
    value.enabled = 1;
    strcpy_s(value.type, type);
    strcpy_s(value.text, text);
}

static FmStep step(bool child, const char *type = "", int position = 0) {
    FmStep result{};
    result.child = child;
    result.position = position;
    result.enabled = -1;
    strcpy_s(result.type, type);
    return result;
}

template <class F> static void rejects(F operation) {
    bool rejected = false;
    try {
        operation();
    } catch (const std::runtime_error &) {
        rejected = true;
    }
    assert(rejected);
}

int main() {
    auto result = std::make_unique<FmResult>();
    result->count = 7;
    node(*result, 0, 2, "class agui::TextButton", "A");
    node(*result, 1, 2, "agui::TextButton", "B");
    node(*result, 2, 1, "Panel");
    node(*result, 3, 3, "Label", "C");
    node(*result, 4, 2, "agui::TextButton", "C");
    node(*result, 5, 1, "Panel");
    node(*result, 6, 0, "Root");
    FmAction action{};
    action.count = 2;
    action.path[0] = step(false, "Panel");
    action.path[1] = step(true, "agui::TextButton", 1);
    assert((selectWidgets(*result, action) == std::vector<int>{0, 4}));
    assert((observationWidgets(*result, true) == std::vector<int>{0, 3, 4}));
    assert((observationWidgets(*result, false) == std::vector<int>{0, 1, 2, 3, 4, 5, 6}));
    action.path[1] = step(false, "agui::TextButton", 2);
    assert((selectWidgets(*result, action) == std::vector<int>{1}));
    assert(!result->nodes[0].selected && !result->nodes[4].selected);

    // Overlapping contexts deduplicate targets, with position evaluated within each context.
    action.path[0] = step(false);
    action.path[1] = step(false, "agui::TextButton", 1);
    assert((selectWidgets(*result, action) == std::vector<int>{0, 4}));
    action.count = 3;
    action.path[0] = step(true, "Root");
    action.path[1] = step(true, "Panel", 2);
    action.path[2] = step(false, "Label");
    assert((selectWidgets(*result, action) == std::vector<int>{3}));

    action.count = 1;
    action.path[0] = step(false, "agui::TextButton");
    action.path[0].hasText = 1;
    strcpy_s(action.path[0].text, "B");
    assert((selectWidgets(*result, action) == std::vector<int>{1}));
    result->nodes[1].truncated = 1;
    assert(selectWidgets(*result, action).empty());
    result->nodes[1].truncated = 2;
    assert(selectWidgets(*result, action).empty());
    result->nodes[1].truncated = 0;
    result->nodes[1].enabled = 0;
    action.path[0].enabled = 1;
    assert(selectWidgets(*result, action).empty());
    action.path[0].enabled = 0;
    assert((selectWidgets(*result, action) == std::vector<int>{1}));
    result->truncated = 1;
    rejects([&] { selectWidgets(*result, action); });
    rejects([&] { observationWidgets(*result, true); });
    assert(observationWidgets(*result, false).size() == static_cast<size_t>(result->count));
    result->truncated = 0;
    result->nodes[0].depth = 4;
    rejects([&] { selectWidgets(*result, action); });
    result->nodes[0].depth = 2;

    action.path[0] = step(false);
    strcpy_s(action.path[0].prototypeName, "iron-plate");
    assert(selectWidgets(*result, action).empty());
    result->nodes[1].properties = 32;
    strcpy_s(result->nodes[1].prototypeName, "iron-plate");
    strcpy_s(result->nodes[1].prototypeType, "class ItemPrototype");
    assert((selectWidgets(*result, action) == std::vector<int>{1}));
    strcpy_s(action.path[0].prototypeType, "RecipePrototype");
    assert(selectWidgets(*result, action).empty());
    strcpy_s(action.path[0].prototypeType, "ItemPrototype");
    assert((selectWidgets(*result, action) == std::vector<int>{1}));
    result->nodes[1].identityTruncated = 1;
    assert(selectWidgets(*result, action).empty());
    result->nodes[1].identityTruncated = 2;
    assert(selectWidgets(*result, action).empty());
    result->nodes[1].identityTruncated = 0;

    action.path[0].hasVisible = 1;
    action.path[0].visible = 1;
    assert(selectWidgets(*result, action).empty());
    result->nodes[1].properties |= 128;
    result->nodes[1].visible = 1;
    assert((selectWidgets(*result, action) == std::vector<int>{1}));
    action.path[0].visible = 0;
    assert(selectWidgets(*result, action).empty());
    result->nodes[1].visible = 0;
    assert((selectWidgets(*result, action) == std::vector<int>{1}));
    action.path[0].hasVisible = 0;
    assert((selectWidgets(*result, action) == std::vector<int>{1}));

    // Forest roots retain document order; child from the virtual root does not mean any depth.
    result->count = 8;
    node(*result, 7, 0, "Root");
    action.path[0] = step(true);
    assert((selectWidgets(*result, action) == std::vector<int>{6, 7}));
    assert(observationWidgets(*result, true).size() == 8);
    for (int i = 0; i < result->count; ++i)
        result->nodes[i].selected = 0;
    assert(observationWidgets(*result, true).empty());
    result->nodes[2].selected = 1;
    result->nodes[0].selected = 1;
    result->nodes[7].selected = 1;
    assert((observationWidgets(*result, true) == std::vector<int>{0, 1, 2, 7}));

    // The old whole-tree scan exceeded its work cap on this ordinary two-step selector.
    constexpr int branches = 2047;
    result->count = branches * 2 + 1;
    std::vector<int> expected;
    for (int i = 0; i < branches; ++i) {
        node(*result, i * 2, 2, "Button");
        node(*result, i * 2 + 1, 1, "Panel");
        expected.push_back(i * 2);
    }
    node(*result, branches * 2, 0, "Root");
    action.count = 2;
    action.path[0] = step(false, "Panel");
    action.path[1] = step(true, "Button");
    assert(selectWidgets(*result, action) == expected);
    action.path[1] = step(false, "Button");
    assert(selectWidgets(*result, action) == expected);

    // Pathological overlapping descendant searches still have a finite work bound.
    result->count = FM_MAX_NODES;
    for (int i = 0; i < FM_MAX_NODES; ++i)
        node(*result, i, FM_MAX_NODES - i - 1, "Panel");
    action.path[1] = step(false, "Absent");
    rejects([&] { selectWidgets(*result, action); });
    result->count = 0;
    assert(selectWidgets(*result, action).empty());
}
