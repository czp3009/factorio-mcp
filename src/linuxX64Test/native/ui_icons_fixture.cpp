#include "ui_icons.h"
#include <cassert>
#include <cerrno>
#include <typeinfo>

struct Widget {
    virtual ~Widget() = default;
    unsigned char padding[FIXTURE_PADDING + 1]{};
};

struct Icon {
    virtual ~Icon() = default;
    unsigned char padding[FIXTURE_PADDING + 7]{};
    const void *disabled = nullptr, *normal = nullptr, *hovered = nullptr;
};

struct Button : Widget, Icon {};

struct VirtualButton : Widget, virtual Icon {};

struct Left : Icon {};

struct Right : Icon {};

struct Ambiguous : Widget, Left, Right {};

static FmLinuxIconLayout layout() {
    Icon icon;
    auto offset = [&](const void *field) {
        return static_cast<uint32_t>(static_cast<const char *>(field) - reinterpret_cast<const char *>(&icon));
    };
    FmLinuxIconLayout result{};
    result.widgetType = reinterpret_cast<uintptr_t>(&typeid(Widget));
    result.type = reinterpret_cast<uintptr_t>(&typeid(Icon));
    result.iconSize = sizeof(Icon);
    result.normal = offset(&icon.normal);
    result.hovered = offset(&icon.hovered);
    result.disabled = offset(&icon.disabled);
    return result;
}

template <class T> static void check(const FmLinuxIconLayout &config) {
    T button;
    Widget *widget = &button;
    assert(reinterpret_cast<void *>(widget) != static_cast<Icon *>(&button));
    // Opaque references do not require reading image storage.
    button.normal = button.hovered = reinterpret_cast<const void *>(1);
    FmLinuxUiNode node{};
    UiIcons icons(config);
    assert(icons.collect(reinterpret_cast<uintptr_t>(widget), node) == 0);
    assert(node.iconsAvailable && node.iconNormal == 0 && node.iconHovered == 0 && node.iconDisabled == -1);
    std::array<int, FM_LINUX_MAX_ICON_REFERENCES + 1> sources{};
    button.hovered = nullptr;
    UiIcons bounded(config);
    for (unsigned index = 0; index < sources.size(); ++index) {
        button.normal = &sources[index];
        assert(bounded.collect(reinterpret_cast<uintptr_t>(widget), node) == 0);
        assert(node.iconNormal == (index < FM_LINUX_MAX_ICON_REFERENCES ? static_cast<int>(index) : -2));
    }
    button.normal = &sources[0];
    assert(bounded.collect(reinterpret_cast<uintptr_t>(widget), node) == 0 && node.iconNormal == 0);
    UiIcons fresh(config);
    button.normal = &sources.back();
    assert(fresh.collect(reinterpret_cast<uintptr_t>(widget), node) == 0 && node.iconNormal == 0);
}

int main() {
    const auto config = layout();
    assert(validIconLayout(config) && validIconLayout({}));
    check<Button>(config);
    check<VirtualButton>(config);
    Widget plain;
    Ambiguous ambiguous;
    FmLinuxUiNode node{};
    UiIcons icons(config);
    assert(icons.collect(reinterpret_cast<uintptr_t>(&plain), node) == 0 && !node.iconsAvailable);
    assert(icons.collect(reinterpret_cast<uintptr_t>(static_cast<Widget *>(&ambiguous)), node) == 0 &&
           !node.iconsAvailable);
    assert(icons.collect(1, node) == EFAULT);
    auto invalid = config;
    invalid.disabled = invalid.normal;
    assert(!validIconLayout(invalid));
    invalid = config;
    invalid.normal = config.iconSize;
    assert(!validIconLayout(invalid));
    invalid = config;
    ++invalid.normal;
    assert(!validIconLayout(invalid));
    invalid = {};
    invalid.disabled = 1;
    assert(!validIconLayout(invalid));
}
