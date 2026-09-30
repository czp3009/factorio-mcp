#include "ui_selector.h"
#include "../../nativeMain/native/widget_selection.h"

namespace {
std::vector<fm::ui::Node> selectionNodes(const FmResult &result) {
    fm::ui::require(result.count >= 0 && result.count <= FM_MAX_NODES, "Invalid UI node count");
    std::vector<fm::ui::Node> nodes(result.count);
    for (int index = 0; index < result.count; ++index) {
        const auto &source = result.nodes[index];
        auto &node = nodes[index];
        node.depth = source.depth;
        node.enabled = source.enabled != 0;
        if (source.properties & 128)
            node.visible = source.visible != 0;
        node.type = {fm::ui::terminated(source.type), true, (source.truncated & 2) != 0};
        node.text = {fm::ui::terminated(source.text), true, (source.truncated & 1) != 0};
        node.prototypeName = {fm::ui::terminated(source.prototypeName), (source.properties & 32) != 0,
                              (source.identityTruncated & 1) != 0};
        node.prototypeType = {fm::ui::terminated(source.prototypeType), (source.properties & 32) != 0,
                              (source.identityTruncated & 2) != 0};
    }
    fm::ui::postorderParents(nodes);
    return nodes;
}

fm::ui::Step selectionStep(const FmStep &source) {
    fm::ui::require(source.enabled >= -1 && source.enabled <= 1, "Invalid enabled predicate");
    fm::ui::Step step;
    step.child = source.child != 0;
    step.position = source.position;
    if (source.enabled >= 0)
        step.enabled = source.enabled != 0;
    if (source.hasVisible)
        step.visible = source.visible != 0;
    if (source.type[0])
        step.type = fm::ui::terminated(source.type);
    if (source.hasText)
        step.text = fm::ui::terminated(source.text);
    if (source.prototypeName[0])
        step.prototypeName = fm::ui::terminated(source.prototypeName);
    if (source.prototypeType[0])
        step.prototypeType = fm::ui::terminated(source.prototypeType);
    return step;
}
} // namespace

std::vector<int> observationWidgets(const FmResult &result, bool filtered) {
    fm::ui::require(result.count >= 0 && result.count <= FM_MAX_NODES, "Invalid UI node count");
    std::vector<int> indices;
    for (int index = 0; index < result.count; ++index) {
        if (!filtered || result.nodes[index].selected)
            indices.push_back(index);
    }
    if (!filtered)
        return indices;
    const auto nodes = selectionNodes(result);
    return fm::ui::Tree(nodes, !result.truncated).observation(indices);
}

std::vector<int> selectWidgets(FmResult &result, const FmAction &action) {
    fm::ui::require(action.count > 0 && action.count <= FM_MAX_PATH, "Invalid selector length");
    const auto nodes = selectionNodes(result);
    const fm::ui::Tree tree(nodes, !result.truncated);
    std::vector<fm::ui::Step> path;
    for (int index = 0; index < action.count; ++index)
        path.push_back(selectionStep(action.path[index]));
    for (int index = 0; index < result.count; ++index)
        result.nodes[index].selected = 0;
    auto selected = tree.select(path, [] { return false; });
    for (int index : selected)
        result.nodes[index].selected = 1;
    return selected;
}
