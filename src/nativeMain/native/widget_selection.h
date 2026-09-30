#pragma once
#include <algorithm>
#include <optional>
#include <stdexcept>
#include <string_view>
#include <vector>

namespace fm::ui {
enum class Failure { invalid, incomplete, workLimit, canceled };

class Error : public std::runtime_error {
public:
    Failure reason;
    Error(Failure reason, const char *message) : std::runtime_error(message), reason(reason) {}
};

inline void require(bool value, const char *message) {
    if (!value)
        throw Error(Failure::invalid, message);
}

struct Text {
    std::string_view value;
    bool available = false;
    bool truncated = false;
};

struct Node {
    int parent = -1;
    int depth = 0;
    bool enabled = false;
    std::optional<bool> visible;
    Text type, text, prototypeName, prototypeType;
};

struct Step {
    bool child = false;
    int position = 0;
    std::optional<bool> enabled, visible;
    std::optional<std::string_view> type, text, prototypeName, prototypeType;
};

template <size_t Capacity> std::string_view terminated(const char (&text)[Capacity]) {
    const auto end = std::find(text, text + Capacity, '\0');
    require(end != text + Capacity, "Selector string is not terminated");
    return {text, static_cast<size_t>(end - text)};
}

inline std::string_view nativeType(std::string_view value) {
    if (value.substr(0, 6) == "class ")
        value.remove_prefix(6);
    return value;
}

inline bool matches(const Node &node, const Step &step) {
    auto text = [](const Text &observed, const std::optional<std::string_view> &expected, bool type = false) {
        return !expected || (observed.available && !observed.truncated &&
            (type ? nativeType(observed.value) : observed.value) == *expected);
    };
    return (!step.enabled || node.enabled == *step.enabled) &&
           (!step.visible || node.visible == step.visible) && text(node.type, step.type, true) &&
           text(node.text, step.text) && text(node.prototypeName, step.prototypeName) &&
           text(node.prototypeType, step.prototypeType, true);
}

/** Only complete postorder forests can establish parents for selector resolution. */
inline void postorderParents(std::vector<Node> &nodes) {
    require(nodes.size() <= 4096, "UI node count exceeds selector bound");
    std::vector<int> pending;
    for (int index = 0; index < static_cast<int>(nodes.size()); ++index) {
        auto &node = nodes[index];
        require(node.depth >= 0, "Invalid UI ancestry");
        node.parent = -1;
        while (!pending.empty() && nodes[pending.back()].depth > node.depth) {
            const int child = pending.back();
            pending.pop_back();
            require(nodes[child].depth == node.depth + 1, "Incomplete UI ancestry");
            nodes[child].parent = index;
        }
        pending.push_back(index);
    }
    for (int index : pending)
        require(nodes[index].depth == 0, "UI forest has a missing parent");
}

/** Input IDs remain unchanged; sibling order is their observed order in either native traversal. */
class Tree {
    const std::vector<Node> &nodes;
    std::vector<std::vector<int>> children;
    std::vector<int> order, ranks, ends;

public:
    Tree(const std::vector<Node> &nodes, bool complete) : nodes(nodes) {
        if (!complete)
            throw Error(Failure::incomplete, "UI search is incomplete; cannot resolve selector safely");
        require(nodes.size() <= 4096, "UI node count exceeds selector bound");
        const int count = static_cast<int>(nodes.size());
        children.resize(count + 1);
        ranks.resize(count);
        ends.resize(count);
        for (int index = 0; index < count; ++index) {
            const auto &node = nodes[index];
            require(node.parent >= -1 && node.parent < count && node.parent != index && node.depth >= 0 &&
                    node.depth < count, "Invalid UI parent or depth");
            require(node.parent < 0 ? node.depth == 0 : nodes[node.parent].depth == node.depth - 1,
                    "Incomplete UI ancestry");
            children[node.parent < 0 ? count : node.parent].push_back(index);
        }
        struct Visit { int index; bool exit; };
        std::vector<Visit> pending{{count, false}};
        while (!pending.empty()) {
            const auto [index, exit] = pending.back();
            pending.pop_back();
            if (exit) {
                ends[index] = static_cast<int>(order.size());
                continue;
            }
            if (index < count) {
                ranks[index] = static_cast<int>(order.size());
                order.push_back(index);
                pending.push_back({index, true});
            }
            for (auto child = children[index].rbegin(); child != children[index].rend(); ++child)
                pending.push_back({*child, false});
        }
        require(order.size() == nodes.size(), "UI forest is disconnected or cyclic");
    }

    template <class Canceled> std::vector<int> select(const std::vector<Step> &path, Canceled canceled) const {
        require(!path.empty() && path.size() <= 16, "Invalid selector length");
        const int count = static_cast<int>(nodes.size());
        std::vector<int> current{count};
        unsigned work = 0;
        for (const auto &step : path) {
            require(step.position >= 0, "Invalid selector position");
            if (canceled())
                throw Error(Failure::canceled, "UI selection canceled");
            std::vector<bool> selected(count);
            for (int context : current) {
                int position = 0;
                auto consider = [&](int index) {
                    if (++work > 2000000)
                        throw Error(Failure::workLimit, "Selector work exceeds bound; use a more specific path");
                    if (canceled())
                        throw Error(Failure::canceled, "UI selection canceled");
                    if (!matches(nodes[index], step))
                        return false;
                    if (!step.position || ++position == step.position) {
                        selected[index] = true;
                        return step.position != 0;
                    }
                    return false;
                };
                if (step.child) {
                    for (int index : children[context]) {
                        if (consider(index))
                            break;
                    }
                } else {
                    const int begin = context == count ? 0 : ranks[context] + 1;
                    const int end = context == count ? count : ends[context];
                    for (int rank = begin; rank < end; ++rank) {
                        if (consider(order[rank]))
                            break;
                    }
                }
            }
            current.clear();
            for (int index : order) {
                if (selected[index])
                    current.push_back(index);
            }
        }
        return current;
    }

    std::vector<int> observation(const std::vector<int> &matches) const {
        std::vector<bool> selected(nodes.size());
        for (int index : matches) {
            require(index >= 0 && index < static_cast<int>(nodes.size()), "Invalid selected widget");
            selected[index] = true;
        }
        for (int index : order) {
            const int parent = nodes[index].parent;
            if (parent >= 0 && selected[parent])
                selected[index] = true;
        }
        std::vector<int> output;
        for (int index = 0; index < static_cast<int>(nodes.size()); ++index) {
            if (selected[index])
                output.push_back(index);
        }
        return output;
    }
};
} // namespace fm::ui
