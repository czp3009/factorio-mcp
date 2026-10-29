#include "ui_selector.h"
#include <cassert>
#include <cstring>
#include <memory>
#include <stdexcept>

static void node(ObservationStorage &storage, int index, int depth, const char *type, const char *text = "") {
    if (storage.nodes.size() <= size_t(index))
        storage.nodes.resize(index + 1);
    auto &value = storage.nodes[index];
    value = {};
    value.depth = depth;
    value.enabled = 1;
    strcpy_s(value.type, type);
    bool truncated = false;
    value.text = storage.copyText(text, strlen(text), truncated);
    assert(!truncated);
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
    ObservationStorage storage;
    result->count = 7;
    node(storage, 0, 2, "class agui::TextButton", "A");
    node(storage, 1, 2, "agui::TextButton", "B");
    node(storage, 2, 1, "Panel");
    node(storage, 3, 3, "Label", "C");
    node(storage, 4, 2, "agui::TextButton", "C");
    node(storage, 5, 1, "Panel");
    node(storage, 6, 0, "Root");
    FmAction action{};
    action.count = 2;
    action.path[0] = step(false, "Panel");
    action.path[1] = step(true, "agui::TextButton", 1);
    assert((selectWidgets(*result, storage, action) == std::vector<int>{0, 4}));
    assert((observationWidgets(*result, storage, true) == std::vector<int>{0, 3, 4}));
    assert((observationWidgets(*result, storage, false) == std::vector<int>{0, 1, 2, 3, 4, 5, 6}));
    action.path[1] = step(false, "agui::TextButton", 2);
    assert((selectWidgets(*result, storage, action) == std::vector<int>{1}));
    assert(!storage.nodes[0].selected && !storage.nodes[4].selected);

    // Overlapping contexts deduplicate targets, with position evaluated within each context.
    action.path[0] = step(false);
    action.path[1] = step(false, "agui::TextButton", 1);
    assert((selectWidgets(*result, storage, action) == std::vector<int>{0, 4}));
    action.count = 3;
    action.path[0] = step(true, "Root");
    action.path[1] = step(true, "Panel", 2);
    action.path[2] = step(false, "Label");
    assert((selectWidgets(*result, storage, action) == std::vector<int>{3}));

    action.count = 1;
    action.path[0] = step(false, "agui::TextButton");
    action.path[0].hasText = 1;
    strcpy_s(action.path[0].text, "B");
    assert((selectWidgets(*result, storage, action) == std::vector<int>{1}));
    storage.nodes[1].truncated = 1;
    assert(selectWidgets(*result, storage, action).empty());
    storage.nodes[1].truncated = 2;
    assert(selectWidgets(*result, storage, action).empty());
    storage.nodes[1].truncated = 0;
    storage.nodes[1].enabled = 0;
    action.path[0].enabled = 1;
    assert(selectWidgets(*result, storage, action).empty());
    action.path[0].enabled = 0;
    assert((selectWidgets(*result, storage, action) == std::vector<int>{1}));
    result->truncated = 1;
    rejects([&] { selectWidgets(*result, storage, action); });
    rejects([&] { observationWidgets(*result, storage, true); });
    assert(observationWidgets(*result, storage, false).size() == static_cast<size_t>(result->count));
    result->truncated = 0;
    storage.nodes[0].depth = 4;
    rejects([&] { selectWidgets(*result, storage, action); });
    storage.nodes[0].depth = 2;

    action.path[0] = step(false);
    strcpy_s(action.path[0].prototypeName, "iron-plate");
    assert(selectWidgets(*result, storage, action).empty());
    storage.nodes[1].properties = 32;
    strcpy_s(storage.nodes[1].prototypeName, "iron-plate");
    strcpy_s(storage.nodes[1].prototypeType, "class ItemPrototype");
    assert((selectWidgets(*result, storage, action) == std::vector<int>{1}));
    strcpy_s(action.path[0].prototypeType, "RecipePrototype");
    assert(selectWidgets(*result, storage, action).empty());
    strcpy_s(action.path[0].prototypeType, "ItemPrototype");
    assert((selectWidgets(*result, storage, action) == std::vector<int>{1}));
    storage.nodes[1].identityTruncated = 1;
    assert(selectWidgets(*result, storage, action).empty());
    storage.nodes[1].identityTruncated = 2;
    assert(selectWidgets(*result, storage, action).empty());
    storage.nodes[1].identityTruncated = 0;

    action.path[0].hasVisible = 1;
    action.path[0].visible = 1;
    assert(selectWidgets(*result, storage, action).empty());
    storage.nodes[1].properties |= 128;
    storage.nodes[1].visible = 1;
    assert((selectWidgets(*result, storage, action) == std::vector<int>{1}));
    action.path[0].visible = 0;
    assert(selectWidgets(*result, storage, action).empty());
    storage.nodes[1].visible = 0;
    assert((selectWidgets(*result, storage, action) == std::vector<int>{1}));
    action.path[0].hasVisible = 0;
    assert((selectWidgets(*result, storage, action) == std::vector<int>{1}));

    // Forest roots retain document order; child from the virtual root does not mean any depth.
    result->count = 8;
    node(storage, 7, 0, "Root");
    action.path[0] = step(true);
    assert((selectWidgets(*result, storage, action) == std::vector<int>{6, 7}));
    assert(observationWidgets(*result, storage, true).size() == 8);
    for (int i = 0; i < result->count; ++i)
        storage.nodes[i].selected = 0;
    assert(observationWidgets(*result, storage, true).empty());
    storage.nodes[2].selected = 1;
    storage.nodes[0].selected = 1;
    storage.nodes[7].selected = 1;
    assert((observationWidgets(*result, storage, true) == std::vector<int>{0, 1, 2, 7}));

    // The old whole-tree scan exceeded its work cap on this ordinary two-step selector.
    constexpr int branches = 3000;
    result->count = branches * 2 + 1;
    std::vector<int> expected;
    for (int i = 0; i < branches; ++i) {
        node(storage, i * 2, 2, "Button");
        node(storage, i * 2 + 1, 1, "Panel");
        expected.push_back(i * 2);
    }
    node(storage, branches * 2, 0, "Root");
    action.count = 2;
    action.path[0] = step(false, "Panel");
    action.path[1] = step(true, "Button");
    assert(selectWidgets(*result, storage, action) == expected);
    action.path[1] = step(false, "Button");
    assert(selectWidgets(*result, storage, action) == expected);

    // Complete overlapping descendant searches preserve the shared selector contract.
    result->count = 6000;
    storage.nodes.resize(result->count);
    for (int i = 0; i < result->count; ++i)
        node(storage, i, result->count - i - 1, "Panel");
    action.path[1] = step(false, "Absent");
    assert(selectWidgets(*result, storage, action).empty());
    volatile LONG cancel = 1;
    rejects([&] { selectWidgets(*result, storage, action, &cancel); });
    result->count = 0;
    storage.nodes.clear();
    assert(selectWidgets(*result, storage, action).empty());
}
