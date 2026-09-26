#include "widget_properties.h"
#include <cassert>
#include <cstddef>
#include <initializer_list>

namespace {
struct Widget {
    unsigned padding[3];
    unsigned flags;
    Widget *parent;
};
} // namespace

int main() {
    Symbols symbols{};
    symbols.widgetParentOffset = offsetof(Widget, parent);
    symbols.visibility.flags = offsetof(Widget, flags);
    Widget parent{}, child{};
    child.parent = &parent;
    auto collect = [&] {
        FmNode node{};
        collectVisibility(symbols, &child, node);
        return node;
    };
    assert(collect().properties == 0);
    symbols.visibility.supported = 1;
    for (unsigned shift : {0u, 5u, 16u}) {
        auto &layout = symbols.visibility;
        layout.visible = 1u << shift;
        layout.render = 2u << shift;
        layout.hiddenBySearch = 4u << shift;
        for (unsigned bits = 0; bits < 8; ++bits) {
            child.flags = bits << shift;
            parent.flags = ~child.flags;
            auto observed = collect();
            assert(observed.properties == 128);
            assert(observed.visible == ((bits & 1) != 0));
            assert(observed.renderEnabled == ((bits & 2) != 0));
            assert(observed.hiddenBySearch == ((bits & 4) != 0));
            // No ancestor traversal or inherited rendering interpretation.
            parent.parent = &child;
            assert(collect().visible == observed.visible);
        }
    }
}