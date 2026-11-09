#include "ui_selector.h"
#include "../../nativeMain/native/ui_fields.h"
#include "../../nativeTest/native/selector_fixture.h"
#include <cassert>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>

static void node(ObservationStorage &storage, int index, int depth, const char *type, const char *text = "") {
    if (storage.nodes.size() <= size_t(index))
        storage.nodes.resize(index + 1);
    auto &value = storage.nodes[index];
    value = {};
    value.depth = depth;
    value.enabled = 1;
    bool truncated = false;
    value.type = storage.copyText(type, strlen(type), truncated);
    value.text = storage.copyText(text, strlen(text), truncated);
    assert(!truncated);
}

static fm::ui::Step step(bool child, const char *type = "", int position = 0) {
    fm::ui::Step result;
    result.child = child;
    result.position = position;
    if (*type) result.type = type;
    return result;
}

static std::vector<int> selected(FmResult &result, ObservationStorage &storage, FmAction &action,
                                 const std::vector<fm::ui::Step> &steps, const volatile LONG *cancel = nullptr) {
    const std::vector<fm::ui::Step> path(steps.begin(), steps.begin() + action.count);
    const auto bytes = selectorBytes(path);
    action.path = {reinterpret_cast<uintptr_t>(bytes.data()), bytes.size()};
    return selectWidgets(result, storage, action, cancel);
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
    {
        FmResult bounded{};
        ObservationStorage copied;
        bounded.count = 4;
        node(copied, 0, 1, "Button", "retained");
        node(copied, 1, 2, "Label", "omitted");
        node(copied, 2, 1, "Button", "boundary");
        node(copied, 3, 0, "Root");
        retainObservedWidgets(bounded, copied, {0, 2}, 0);
        assert(bounded.count == 2 && copied.nodes.size() == 2 && copied.nodes[0].depth == 0 &&
               copied.nodes[1].depth == 0 && copied.nodes[1].childrenTruncated &&
               copied.nodes[0].siblingIndex == 1 && copied.nodes[1].siblingIndex == 2 &&
               !copied.nodes[0].text.size && !copied.nodes[1].text.size);
        copied.publish(bounded);
        assert(bounded.nodes.size == 2 * sizeof(FmNode));
    }
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
    std::vector<fm::ui::Step> steps(3);
    action.count = 2;
    steps[0] = step(false, "Panel");
    steps[1] = step(true, "agui::TextButton", 1);
    assert((selected(*result, storage, action, steps) == std::vector<int>{0, 4}));
    assert((observationWidgets(*result, storage, true) == std::vector<int>{0, 3, 4}));
    assert((observationWidgets(*result, storage, true, false, 0) == std::vector<int>{0, 4}));
    assert((observationWidgets(*result, storage, false) == std::vector<int>{0, 1, 2, 3, 4, 5, 6}));
    steps[1] = step(false, "agui::TextButton", 2);
    assert((selected(*result, storage, action, steps) == std::vector<int>{1}));
    assert(!storage.nodes[0].selected && !storage.nodes[4].selected);

    // Overlapping contexts deduplicate targets, with position evaluated within each context.
    steps[0] = step(false);
    steps[1] = step(false, "agui::TextButton", 1);
    assert((selected(*result, storage, action, steps) == std::vector<int>{0, 4}));
    action.count = 3;
    steps[0] = step(true, "Root");
    steps[1] = step(true, "Panel", 2);
    steps[2] = step(false, "Label");
    assert((selected(*result, storage, action, steps) == std::vector<int>{3}));

    action.count = 1;
    steps[0] = step(false, "agui::TextButton");
    steps[0].text = "B";
    assert((selected(*result, storage, action, steps) == std::vector<int>{1}));
    storage.nodes[1].truncated = 1;
    assert(selected(*result, storage, action, steps).empty());
    storage.nodes[1].truncated = 2;
    assert(selected(*result, storage, action, steps).empty());
    storage.nodes[1].truncated = 0;
    storage.nodes[1].enabled = 0;
    steps[0].enabled = 1;
    assert(selected(*result, storage, action, steps).empty());
    steps[0].enabled = 0;
    assert((selected(*result, storage, action, steps) == std::vector<int>{1}));
    result->truncated = 1;
    rejects([&] { selected(*result, storage, action, steps); });
    rejects([&] { observationWidgets(*result, storage, true); });
    rejects([&] { observationWidgets(*result, storage, false); });
    result->truncated = 0;
    storage.nodes[0].depth = 4;
    rejects([&] { selected(*result, storage, action, steps); });
    storage.nodes[0].depth = 2;

    steps[0] = step(false);
    steps[0].prototypeName = "iron-plate";
    assert(selected(*result, storage, action, steps).empty());
    storage.nodes[1].properties = 32;
    strcpy_s(storage.nodes[1].prototypeName, "iron-plate");
    bool truncated = false;
    storage.nodes[1].prototypeType = storage.copyText("class ItemPrototype", strlen("class ItemPrototype"), truncated);
    assert((selected(*result, storage, action, steps) == std::vector<int>{1}));
    steps[0].prototypeType = "RecipePrototype";
    assert(selected(*result, storage, action, steps).empty());
    steps[0].prototypeType = "ItemPrototype";
    assert((selected(*result, storage, action, steps) == std::vector<int>{1}));
    storage.nodes[1].identityTruncated = 1;
    assert(selected(*result, storage, action, steps).empty());
    storage.nodes[1].identityTruncated = 0;
    const std::string longType(4096, 'T');
    storage.nodes[1].prototypeType = storage.copyText(longType.data(), longType.size(), truncated);
    steps[0].prototypeType = longType;
    assert((selected(*result, storage, action, steps) == std::vector<int>{1}));

    steps[0].visible = 1;
    assert(selected(*result, storage, action, steps).empty());
    storage.nodes[1].properties |= 128;
    storage.nodes[1].visible = 1;
    assert((selected(*result, storage, action, steps) == std::vector<int>{1}));
    steps[0].visible = 0;
    assert(selected(*result, storage, action, steps).empty());
    storage.nodes[1].visible = 0;
    assert((selected(*result, storage, action, steps) == std::vector<int>{1}));
    steps[0].visible.reset();
    assert((selected(*result, storage, action, steps) == std::vector<int>{1}));

    // Forest roots retain document order; child from the virtual root does not mean any depth.
    result->count = 8;
    node(storage, 7, 0, "Root");
    steps[0] = step(true);
    assert((selected(*result, storage, action, steps) == std::vector<int>{6, 7}));
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
    steps[0] = step(false, "Panel");
    steps[1] = step(true, "Button");
    assert(selected(*result, storage, action, steps) == expected);
    steps[1] = step(false, "Button");
    assert(selected(*result, storage, action, steps) == expected);

    // Complete overlapping descendant searches preserve the shared selector contract.
    result->count = 6000;
    storage.nodes.resize(result->count);
    for (int i = 0; i < result->count; ++i)
        node(storage, i, result->count - i - 1, "Panel");
    steps[1] = step(false, "Absent");
    assert(selected(*result, storage, action, steps).empty());
    volatile LONG cancel = 1;
    rejects([&] { selected(*result, storage, action, steps, &cancel); });
    result->count = 0;
    storage.nodes.clear();
    assert(selected(*result, storage, action, steps).empty());
}
