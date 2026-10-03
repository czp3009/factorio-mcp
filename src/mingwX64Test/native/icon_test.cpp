#include "widget_properties.h"
#include <cassert>
#include <cstddef>

struct Icon {
    void *padding{};
    const void *disabled{}, *normal{}, *hovered{};
};

static Icon *receiver{};

static void *cast(void *widget, long, void *, void *, int) {
    return widget == reinterpret_cast<void *>(1) ? receiver : nullptr;
}

int main() {
    Symbols symbols{};
    symbols.address[DynamicCast] = reinterpret_cast<uint64_t>(cast);
    auto &layout = symbols.icons;
    layout.supported = 1;
    layout.normal = offsetof(Icon, normal);
    layout.hovered = offsetof(Icon, hovered);
    layout.disabled = offsetof(Icon, disabled);
    Icon icon;
    receiver = &icon;
    FmNode node{};
    int source = 0;
    icon.normal = icon.hovered = &source;
    IconReferences references(symbols);
    references.collect(reinterpret_cast<void *>(1), node);
    assert(node.properties == 2048 && node.iconNormal == 0 && node.iconHovered == 0 && node.iconDisabled == -1);
    FmNode unsupported{};
    references.collect(nullptr, unsupported);
    assert(unsupported.properties == 0);
    std::array<int, FM_MAX_ICON_REFERENCES + 1> sources{};
    icon.hovered = nullptr;
    IconReferences bounded(symbols);
    for (unsigned index = 0; index < sources.size(); ++index) {
        icon.normal = &sources[index];
        bounded.collect(reinterpret_cast<void *>(1), node);
        assert(node.iconNormal == (index < FM_MAX_ICON_REFERENCES ? static_cast<int>(index) : -2));
    }
    icon.normal = &sources[0];
    bounded.collect(reinterpret_cast<void *>(1), node);
    assert(node.iconNormal == 0);
    IconReferences fresh(symbols);
    icon.normal = &sources.back();
    fresh.collect(reinterpret_cast<void *>(1), node);
    assert(node.iconNormal == 0);
    layout.supported = 0;
    FmNode unavailable{};
    fresh.collect(reinterpret_cast<void *>(1), unavailable);
    assert(unavailable.properties == 0);
}
