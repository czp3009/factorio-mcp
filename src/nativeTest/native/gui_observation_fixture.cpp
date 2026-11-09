#include "../../nativeMain/native/gui_observation.h"
#include <cassert>
#include <cstddef>
#include <cstring>

namespace {
struct Node {
    Node *left{}, *parent{}, *right{};
    uint8_t nil{};
    uintptr_t widget{};
    uintptr_t element{};
};
struct Gui {
    uint64_t prefix{};
    Node *head{};
    uint64_t count{};
};
struct Player {
    uint64_t prefix{};
    Gui *gui{};
};
struct Element {
    uint64_t prefix{};
    uintptr_t widget{};
    Gui *gui{};
    uint32_t index{};
};
}

int main() {
    FmGuiRegistryLayout layout{};
    layout.supported = 1;
    layout.playerSize = sizeof(Player);
    layout.playerGui = offsetof(Player, gui);
    layout.guiSize = sizeof(Gui);
    layout.mapHead = offsetof(Gui, head);
    layout.mapCount = offsetof(Gui, count);
    layout.nodeSize = sizeof(Node);
    layout.left = offsetof(Node, left);
    layout.right = offsetof(Node, right);
    layout.parent = offsetof(Node, parent);
    layout.nil = offsetof(Node, nil);
    layout.key = offsetof(Node, widget);
    layout.value = offsetof(Node, element);
    layout.elementSize = sizeof(Element);
    layout.elementWidget = offsetof(Element, widget);
    layout.elementGui = offsetof(Element, gui);
    layout.elementIndex = offsetof(Element, index);
    Node head, node;
    Gui gui{0, &head, 1};
    Player player{0, &gui};
    Element element{0, 123, &gui, 0};
    head.nil = 1;
    head.parent = &node;
    node.left = node.right = &head;
    node.widget = element.widget;
    node.element = reinterpret_cast<uintptr_t>(&element);
    auto collect = [&](bool cancel = false) {
        return fm::ui::guiRegistry(reinterpret_cast<uintptr_t>(&player), layout,
            [](uintptr_t address, auto &value) {
                std::memcpy(&value, reinterpret_cast<void *>(address), sizeof(value));
                return true;
            },
            [&] { return cancel; });
    };
    assert(collect().at(123) == 0);
    auto rejects = [&](auto operation) {
        bool rejected = false;
        try {
            operation();
        } catch (const std::exception &) {
            rejected = true;
        }
        assert(rejected);
    };
    rejects([&] { collect(true); });
    element.widget = 124;
    rejects([&] { collect(); });
    element.widget = 123;
    node.left = &node;
    rejects([&] { collect(); });
    node.left = &head;
    gui.count = 2;
    rejects([&] { collect(); });
    gui.count = 1;
    auto payload = fm::ui::guiIndexPayload(1, {-1, 0, UINT32_MAX});
    assert(payload.size() == 32 && payload[0] == 1 && payload[4] == 3 && payload[12] == 0 && payload[20] == 1);
    rejects([&] { fm::ui::guiIndexPayload(0, {}); });
    rejects([&] { fm::ui::guiIndexPayload(1, {-2}); });
    rejects([&] { fm::ui::guiIndexPayload(2, {int64_t(UINT32_MAX) + 1}); });
    assert(fm::ui::guiMatchSize(3, 256) == 768);
    rejects([&] { fm::ui::guiMatchSize(1, 0); });
    rejects([&] { fm::ui::guiMatchSize(INT_MAX, 2); });
}
