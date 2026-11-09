#include "ui_icons.h"
#include "ui_tooltips.h"
#include <cassert>
#include <cerrno>
#include <typeinfo>
#include <string>

struct StoredText {
    const char *data;
    uint64_t length;
};

struct Provider {
    virtual ~Provider() = default;
    virtual void create() { assert(false); }
};

struct Plain : Provider {
    unsigned char padding[FIXTURE_PADDING + 17]{};
    StoredText title{"title", 5}, text{"raw\n[item=iron-plate]", 21};
};
struct Callback : Plain {
    void create() override { assert(false); }
};

struct TooltipWidget {
    unsigned char padding[FIXTURE_PADDING + 9]{};
    Provider *creator{};
};

static int lookups, destroyed;

static void lookup(void *output, uintptr_t registry, uintptr_t sprite) {
    assert(registry && sprite);
    ++lookups;
    new (output) StoredText{"fixture_registered_icon", 23};
}
static void destroy(void *) {
    ++destroyed;
}

static void tooltips() {
    Callback provider;
    TooltipWidget widget;
    auto offset = [](const void *object, const void *field) {
        return static_cast<uint32_t>(static_cast<const char *>(field) - static_cast<const char *>(object));
    };
    FmLinuxTooltipLayout layout{reinterpret_cast<uintptr_t>(&typeid(Provider)), reinterpret_cast<uintptr_t>(&typeid(Plain)),
        sizeof(Plain), offset(&widget, &widget.creator), offset(static_cast<Plain *>(&provider), &provider.title),
        offset(static_cast<Plain *>(&provider), &provider.text), sizeof(StoredText), offsetof(StoredText, data), offsetof(StoredText, length)};
    UiSnapshot storage;
    FmLinuxUiNode node{};
    collectTooltip(reinterpret_cast<uintptr_t>(&widget), layout, node, storage);
    assert(node.tooltipStatus == 0);
    Provider dynamic;
    widget.creator = &dynamic;
    collectTooltip(reinterpret_cast<uintptr_t>(&widget), layout, node, storage);
    assert(node.tooltipStatus == 2);
    widget.creator = &provider;
    collectTooltip(reinterpret_cast<uintptr_t>(&widget), layout, node, storage);
    assert(node.tooltipStatus == 3 && node.tooltipTitle.size == 5 && node.tooltipText.size == 21);
    std::string large((16 * 1024 * 1024) + 9, 'x');
    provider.text = {large.data(), large.size()};
    collectTooltip(reinterpret_cast<uintptr_t>(&widget), layout, node, storage);
    assert(node.tooltipTruncated == 0 && node.tooltipText.size == large.size());
    provider.text = {reinterpret_cast<const char *>(1), 20};
    collectTooltip(reinterpret_cast<uintptr_t>(&widget), layout, node, storage);
    assert(node.tooltipStatus == 4);
}

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

struct Image {
    virtual ~Image() = default;
    virtual void render() { assert(false); }
};

struct StaticImage : Image {
    unsigned char padding[FIXTURE_PADDING + 5]{};
    const void *sprite = nullptr;
};

struct DynamicImage : Image {};

struct ImageWidget : Widget {
    unsigned char padding[FIXTURE_PADDING + 23]{};
    Image *image = nullptr;
};

static void images() {
    ImageWidget widget;
    StaticImage plain;
    auto offset = [](const void *object, const void *field) {
        return static_cast<uint32_t>(static_cast<const char *>(field) - static_cast<const char *>(object));
    };
    FmUiImageLayout config{reinterpret_cast<uintptr_t>(&typeid(ImageWidget)), reinterpret_cast<uintptr_t>(&typeid(Image)),
        reinterpret_cast<uintptr_t>(&typeid(StaticImage)), 1, sizeof(ImageWidget), offset(&widget, &widget.image),
        sizeof(Image), sizeof(StaticImage), offset(&plain, &plain.sprite)};
    assert(validUiImageLayout(config));
    FmLinuxIconLayout iconLayout{};
    UiIcons icons(iconLayout);
    UiSnapshot storage;
    FmLinuxIconNamesLayout names{};
    FmLinuxUiNode node{};
    const auto address = reinterpret_cast<uintptr_t>(static_cast<Widget *>(&widget));
    const auto base = reinterpret_cast<uintptr_t>(&typeid(Widget));
    assert(icons.collectImage(address, base, config, node, names, storage) == 0 && node.image.status == 1);
    widget.image = &plain;
    plain.sprite = reinterpret_cast<const void *>(1);
    node = {};
    assert(icons.collectImage(address, base, config, node, names, storage) == 0);
    assert(node.image.status == 2 && node.image.spriteObserved == 1 && node.image.reference == 0);
    const auto retained = node.image.type;
    assert(retained.size && std::string(reinterpret_cast<const char *>(retained.address), retained.size) == "StaticImage");
    DynamicImage dynamic;
    widget.image = &dynamic;
    node = {};
    assert(icons.collectImage(address, base, config, node, names, storage) == 0);
    assert(node.image.status == 2 && !node.image.spriteObserved && !node.image.name.size);
    assert(std::string(reinterpret_cast<const char *>(retained.address), retained.size) == "StaticImage");
    Widget other;
    node = {};
    assert(icons.collectImage(reinterpret_cast<uintptr_t>(&other), base, config, node, names, storage) == 0 && !node.image.status);
    config.sprite = config.staticSize;
    assert(!validUiImageLayout(config));
    assert(icons.collectImage(address, base, config, node, names, storage) == EINVAL);
}

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
    std::array<int, 5000> sources{};
    button.hovered = nullptr;
    UiIcons bounded(config);
    for (unsigned index = 0; index < sources.size(); ++index) {
        button.normal = &sources[index];
        assert(bounded.collect(reinterpret_cast<uintptr_t>(widget), node) == 0);
        assert(node.iconNormal == static_cast<int>(index));
    }
    button.normal = &sources[0];
    assert(bounded.collect(reinterpret_cast<uintptr_t>(widget), node) == 0 && node.iconNormal == 0);
    UiIcons fresh(config);
    button.normal = &sources.back();
    assert(fresh.collect(reinterpret_cast<uintptr_t>(widget), node) == 0 && node.iconNormal == 0);
}

int main() {
    images();
    tooltips();
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
    struct TestGlobal {
        unsigned char padding[51];
        uintptr_t registry;
    } global{};
    global.registry = 1;
    uintptr_t root = reinterpret_cast<uintptr_t>(&global);
    FmLinuxIconNamesLayout names{reinterpret_cast<uintptr_t>(&root), reinterpret_cast<uintptr_t>(lookup),
        reinterpret_cast<uintptr_t>(destroy), offsetof(TestGlobal, registry), sizeof(StoredText),
        offsetof(StoredText, data), offsetof(StoredText, length)};
    Button button;
    button.normal = button.hovered = reinterpret_cast<const void *>(1);
    UiSnapshot storage;
    UiIcons named(config);
    assert(named.collect(reinterpret_cast<uintptr_t>(static_cast<Widget *>(&button)), node, &names, &storage) == 0);
    assert(named.collect(reinterpret_cast<uintptr_t>(static_cast<Widget *>(&button)), node, &names, &storage) == 0);
    assert(lookups == 1 && destroyed == 1 && node.iconNames[0].size == 23);
    assert(node.iconNames[0].address == node.iconNames[1].address);
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
