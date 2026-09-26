#include "ui_selector.h"
#include "protocol.h"
#include <algorithm>
#include <cstring>

std::vector<int> observationWidgets(const FmResult &result, bool filtered) {
    require(result.count >= 0 && result.count <= FM_MAX_NODES, "Invalid UI node count");
    require(!filtered || !result.truncated, "UI search is incomplete; cannot resolve selector safely");
    std::vector<int> indices;
    indices.reserve(result.count);
    int selectedDepth = -1;
    for (int i = result.count - 1; i >= 0; --i) {
        const auto &node = result.nodes[i];
        require(node.depth >= 0, "Invalid UI ancestry");
        if (selectedDepth >= 0 && node.depth <= selectedDepth)
            selectedDepth = -1;
        if (node.selected && selectedDepth < 0)
            selectedDepth = node.depth;
        if (!filtered || selectedDepth >= 0)
            indices.push_back(i);
    }
    std::reverse(indices.begin(), indices.end());
    return indices;
}

std::vector<int> selectWidgets(FmResult &result, const FmAction &action) {
    require(!result.truncated, "UI search is incomplete; cannot resolve selector safely");
    require(result.count >= 0 && result.count <= FM_MAX_NODES, "Invalid UI node count");
    require(action.count > 0 && action.count <= FM_MAX_PATH, "Invalid selector length");
    const int count = result.count;
    std::vector<int> parents(count, -1), starts(count), stack;
    std::vector<std::vector<int>> children(count + 1);
    for (int i = 0; i < count; ++i) {
        require(result.nodes[i].depth >= 0, "Invalid UI ancestry");
        result.nodes[i].selected = 0;
        starts[i] = i;
        while (!stack.empty() && result.nodes[stack.back()].depth > result.nodes[i].depth) {
            const int child = stack.back();
            stack.pop_back();
            require(result.nodes[child].depth == result.nodes[i].depth + 1, "Incomplete UI ancestry");
            parents[child] = i;
            starts[i] = std::min(starts[i], starts[child]);
        }
        stack.push_back(i);
    }
    for (int i = 0; i < count; ++i)
        children[parents[i] < 0 ? count : parents[i]].push_back(i);
    std::vector<int> order, ranks(count);
    order.reserve(count);
    std::vector<int> visit{count};
    while (!visit.empty()) {
        const int i = visit.back();
        visit.pop_back();
        if (i < count) {
            ranks[i] = static_cast<int>(order.size());
            order.push_back(i);
        }
        for (auto child = children[i].rbegin(); child != children[i].rend(); ++child)
            visit.push_back(*child);
    }
    std::vector<int> current{count};
    unsigned work = 0;
    for (int stepIndex = 0; stepIndex < action.count; ++stepIndex) {
        const auto &step = action.path[stepIndex];
        require(step.position >= 0, "Invalid selector position");
        std::vector<bool> selected(count);
        for (int context : current) {
            int position = 0;
            auto consider = [&](int i) {
                require(++work <= 2000000, "Selector work exceeds bound; use a more specific path");
                const auto &node = result.nodes[i];
                if (step.hasVisible && (!(node.properties & 128) || (node.visible != 0) != (step.visible != 0)))
                    return false;
                const char *type = node.type;
                if (strncmp(type, "class ", 6) == 0)
                    type += 6;
                if (step.type[0] && ((node.truncated & 2) || strcmp(type, step.type)))
                    return false;
                if (step.hasText && ((node.truncated & 1) || strcmp(node.text, step.text)))
                    return false;
                if (step.enabled >= 0 && step.enabled != node.enabled)
                    return false;
                if (step.prototypeName[0] && (!(node.properties & 32) || (node.identityTruncated & 1) ||
                                              strcmp(node.prototypeName, step.prototypeName)))
                    return false;
                const char *prototypeType = node.prototypeType;
                if (strncmp(prototypeType, "class ", 6) == 0)
                    prototypeType += 6;
                if (step.prototypeType[0] && (!(node.properties & 32) || (node.identityTruncated & 2) ||
                                              strcmp(prototypeType, step.prototypeType)))
                    return false;
                if (!step.position || ++position == step.position) {
                    selected[i] = true;
                    return step.position != 0;
                }
                return false;
            };
            if (step.child) {
                for (int i : children[context])
                    if (consider(i))
                        break;
            } else {
                // Preorder places each subtree in one interval. Exclude the context itself.
                const int begin = context == count ? 0 : ranks[context] + 1;
                const int end = context == count ? count : begin + context - starts[context];
                for (int rank = begin; rank < end; ++rank)
                    if (consider(order[rank]))
                        break;
            }
        }
        current.clear();
        for (int i : order)
            if (selected[i])
                current.push_back(i);
    }
    for (int i : current)
        result.nodes[i].selected = 1;
    return current;
}
