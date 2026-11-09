#include "ui_selector.h"
#include "../../nativeMain/native/widget_selection.h"
#include "../../nativeMain/native/selector_payload.h"
#include "../../nativeMain/native/ui_fields.h"

namespace {
std::vector<fm::ui::Node> selectionNodes(const FmResult &result, const ObservationStorage &storage) {
    fm::ui::require(result.count >= 0 && size_t(result.count) == storage.nodes.size(), "Invalid UI node count");
    std::vector<fm::ui::Node> nodes(result.count);
    for (int index = 0; index < result.count; ++index) {
        const auto &source = storage.nodes[index];
        auto &node = nodes[index];
        node.depth = source.depth;
        node.enabled = source.enabled != 0;
        if (source.properties & 128)
            node.visible = source.visible != 0;
        node.type = {observationText(source.type), true, (source.truncated & 2) != 0};
        node.text = {observationText(source.text), true, (source.truncated & 1) != 0};
        node.prototypeName = {fm::ui::terminated(source.prototypeName), (source.properties & 32) != 0,
                              (source.identityTruncated & 1) != 0};
        node.prototypeType = {observationText(source.prototypeType), (source.properties & 32) != 0, false};
    }
    fm::ui::postorderParents(nodes);
    return nodes;
}

} // namespace

std::vector<int> observationWidgets(const FmResult &result, const ObservationStorage &storage, bool filtered,
                                    bool collection, int maxDepth) {
    fm::ui::require(result.count >= 0 && size_t(result.count) == storage.nodes.size(), "Invalid UI node count");
    std::vector<int> indices;
    for (int index = 0; index < result.count; ++index) {
        if (filtered ? storage.nodes[index].selected != 0 : storage.nodes[index].depth == 0)
            indices.push_back(index);
    }
    if (collection)
        fm::ui::require(indices.size() == 1, "UI collection selector must resolve exactly one widget");
    if (collection)
        return indices;
    const auto nodes = selectionNodes(result, storage);
    return fm::ui::Tree(nodes, !result.truncated).observation(indices, maxDepth);
}

void retainObservedWidgets(FmResult &result, ObservationStorage &storage, const std::vector<int> &observed,
                          uint32_t fields) {
    const auto nodes = selectionNodes(result, storage);
    const auto retained = fm::ui::Tree(nodes, !result.truncated).retain(observed, true);
    std::vector<FmNode> output;
    output.reserve(retained.size());
    for (const auto &visit : retained) {
        auto node = storage.nodes[visit.source];
        node.depth = visit.depth;
        node.siblingIndex = visit.siblingIndex;
        node.childrenTruncated = visit.childrenTruncated;
        if (!(fields & FM_UI_TEXT)) node.text = {};
        if (!(fields & FM_UI_PROTOTYPE)) {
            node.properties &= ~32u;
            node.prototypeType = {};
            node.prototypeName[0] = 0;
        }
        output.push_back(node);
    }
    storage.nodes = std::move(output);
    result.count = static_cast<int>(storage.nodes.size());
}

std::vector<int> selectWidgets(FmResult &result, ObservationStorage &storage, const FmAction &action,
                               const volatile LONG *cancel) {
    fm::ui::require(action.count > 0, "Invalid selector length");
    const auto nodes = selectionNodes(result, storage);
    auto matchedNodes = nodes;
    if (!storage.guiMatches.empty()) {
        fm::ui::require(storage.guiMatches.size() == size_t(result.count) * action.count,
                        "Invalid script GUI predicate result size");
        for (int index = 0; index < result.count; ++index)
            matchedNodes[index].guiMatches = {storage.guiMatches.data() + size_t(index) * action.count,
                                              static_cast<size_t>(action.count)};
    }
    const fm::ui::Tree matchedTree(matchedNodes, !result.truncated);
    const auto path = fm::ui::selectorPath(action.path, action.count);
    for (int index = 0; index < result.count; ++index)
        storage.nodes[index].selected = 0;
    auto selected = matchedTree.select(path, [cancel] { return cancel && *cancel; });
    for (int index : selected)
        storage.nodes[index].selected = 1;
    return selected;
}
