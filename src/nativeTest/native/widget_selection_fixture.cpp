#include "../../nativeMain/native/widget_selection.h"
#include <cstdio>
#include <cstdlib>

static void require(bool value, const char *message) {
    if (!value) {
        std::fprintf(stderr, "factorio-mcp widget selection fixture: %s\n", message);
        std::abort();
    }
}

template <class Operation> static void rejects(fm::ui::Failure reason, Operation operation) {
    try {
        operation();
    } catch (const fm::ui::Error &error) {
        require(error.reason == reason, "unexpected rejection reason");
        return;
    }
    require(false, "invalid selection was accepted");
}

static fm::ui::Node node(int depth, std::string_view type, std::string_view text = "") {
    fm::ui::Node result;
    result.depth = depth;
    result.enabled = true;
    result.type = {type, true, false};
    result.text = {text, true, false};
    return result;
}

static fm::ui::Step step(bool child, std::optional<std::string_view> type = {}, int position = 0) {
    fm::ui::Step result;
    result.child = child;
    result.type = type;
    result.position = position;
    return result;
}

int main() {
    using fm::ui::Failure;
    using fm::ui::Tree;
    std::vector<fm::ui::Node> nodes{node(2, "class Button", "A"), node(2, "Button", "B"), node(1, "Panel"),
        node(3, "Label", "C"), node(2, "Button", "C"), node(1, "Panel"), node(0, "Root")};
    fm::ui::postorderParents(nodes);
    std::vector<fm::ui::Step> path{step(false, "Panel"), step(true, "Button", 1)};
    auto select = [&] { return Tree(nodes, true).select(path, [] { return false; }); };
    require(Tree(nodes, true).observation({2}, 0) == std::vector<int>{2}, "depth zero includes descendants");
    require(Tree(nodes, true).observation({2}, 1) == std::vector<int>({0, 1, 2}), "depth one loses direct children");
    require(Tree(nodes, true).observation({3, 6}, 0) == std::vector<int>({3, 6}), "nested matches lose independent depth roots");
    const auto bounded = Tree(nodes, true).retain({1, 2, 3}, true);
    require(bounded.size() == 3 && bounded[0].source == 1 && bounded[0].parent == 1 &&
                bounded[0].depth == 1 && bounded[0].siblingIndex == 2 && !bounded[0].childrenTruncated &&
                bounded[1].source == 2 && bounded[1].parent == -1 && bounded[1].depth == 0 &&
                bounded[1].childrenTruncated && bounded[2].parent == -1 && bounded[2].depth == 0,
            "resident pruning loses original sibling positions, independent roots or omitted children");
    rejects(Failure::invalid, [&] { Tree(nodes, true).retain({1, 1}); });
    for (bool postorder : {false, true}) {
        const auto separated = Tree(nodes, true).retain({3, 6}, postorder);
        require(separated.size() == 2 && separated[0].source == 6 && separated[1].source == 3 &&
                    separated[0].parent == -1 && separated[1].parent == -1,
                "pruning changed ancestor/descendant root document order across an omitted path");
    }
    rejects(Failure::invalid, [&] { Tree(nodes, true).observation({2}, -1); });
    require(select() == std::vector<int>{0, 4}, "child position is not per context");
    const auto selected = select();
    require(Tree(nodes, true).observation(selected) == std::vector<int>{0, 3, 4}, "selected subtrees differ");
    path[1] = step(false, "Button", 2);
    require(select() == std::vector<int>{1}, "descendant position differs");
    path[0] = step(false);
    path[1] = step(false, "Button", 1);
    require(select() == std::vector<int>{0, 4}, "overlapping contexts duplicate matches");
    path = {step(true, "Root"), step(true, "Panel", 2), step(false, "Label")};
    require(select() == std::vector<int>{3}, "multi-step ancestry differs");
    path = {step(false, "Button")};
    path[0].text = "B";
    require(select() == std::vector<int>{1}, "exact text match differs");
    nodes[1].text.truncated = true;
    require(select().empty(), "truncated text matched");
    nodes[1].text.truncated = false;
    nodes[1].text.available = false;
    require(select().empty(), "unavailable text matched");
    nodes[1].text.available = true;
    nodes[1].text.value = std::string_view("B\0C", 3);
    require(select().empty(), "embedded null text matched a prefix");
    nodes[1].text.value = "B";
    nodes[1].enabled = false;
    path[0].enabled = true;
    require(select().empty(), "enabled predicate ignored");
    path[0].enabled = false;
    require(select() == std::vector<int>{1}, "own disabled flag was replaced by inherited state");
    path[0].prototypeName = "item";
    path[0].prototypeType = "ItemPrototype";
    require(select().empty(), "unknown prototype matched");
    nodes[1].prototypeName = {"item", true, false};
    nodes[1].prototypeType = {"class ItemPrototype", true, false};
    require(select() == std::vector<int>{1}, "prototype identity differs");
    nodes[1].prototypeType.truncated = true;
    require(select().empty(), "truncated prototype type matched");
    nodes[1].prototypeType.truncated = false;
    path[0].visible = false;
    require(select().empty(), "unknown visibility matched false");
    nodes[1].visible = false;
    require(select() == std::vector<int>{1}, "observed visibility differs");
    path[0].guiMatch = 1;
    require(select().empty(), "unavailable script predicates matched");
    nodes[1].guiMatches = "01";
    require(select() == std::vector<int>{1}, "script predicate was not combined with native predicates");
    nodes[1].guiMatches = "00";
    require(select().empty(), "false script predicate matched");
    path[0].guiMatch = -1;

    // A preorder adapter supplies explicit parents and keeps the same document-order semantics.
    const int order[] = {6, 2, 0, 1, 5, 4, 3};
    const int inverse[] = {2, 3, 1, 6, 5, 4, 0};
    std::vector<fm::ui::Node> preorder;
    for (int index : order) {
        auto value = nodes[index];
        if (value.parent >= 0)
            value.parent = inverse[value.parent];
        preorder.push_back(value);
    }
    require(Tree(preorder, true).select(path, [] { return false; }) == std::vector<int>{3},
            "preorder adapter changes selection semantics");
    rejects(Failure::incomplete, [&] { Tree(nodes, false); });
    rejects(Failure::canceled, [&] { Tree(nodes, true).select(path, [] { return true; }); });
    nodes[0].parent = 0;
    rejects(Failure::invalid, [&] { Tree(nodes, true); });
    nodes[0].parent = 2;
    nodes[0].depth = 4;
    rejects(Failure::invalid, [&] { Tree(nodes, true); });
    rejects(Failure::invalid, [&] { fm::ui::postorderParents(nodes); });
    nodes[0].depth = 2;
    fm::ui::postorderParents(nodes);
    nodes.push_back(node(0, "Root"));
    path = {step(true)};
    require(select() == std::vector<int>({6, 7}), "forest root order differs");
    require(Tree(nodes, true).observation(select()).size() == nodes.size(), "forest expansion loses descendants");

    nodes.clear();
    for (int index = 0; index < 2047; ++index) {
        nodes.push_back(node(2, "Button"));
        nodes.push_back(node(1, "Panel"));
    }
    nodes.push_back(node(0, "Root"));
    fm::ui::postorderParents(nodes);
    path = {step(false, "Panel"), step(false, "Button")};
    require(select().size() == 2047, "ordinary independent branches exceed work bound");
    nodes.clear();
    for (int index = 0; index < 4096; ++index)
        nodes.push_back(node(4095 - index, "Panel"));
    fm::ui::postorderParents(nodes);
    path[1] = step(false, "Absent");
    require(select().empty(), "complete large descendant search was rejected");
    path = std::vector<fm::ui::Step>(256, step(true, "Panel"));
    require(select() == std::vector<int>{3840}, "long child selector was limited by the old path capacity");
    nodes.clear();
    require(select().empty(), "empty forest produces matches");
    const char invalid[] = {'n', 'o'};
    rejects(Failure::invalid, [&] { fm::ui::terminated(invalid); });
}
