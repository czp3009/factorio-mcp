#include "widget_properties.h"
#include <cassert>
#include <cstddef>
#include <array>
#include <string>

struct Icon {
    void *padding{};
    const void *disabled{}, *normal{}, *hovered{};
};

static Icon *receiver{};
static int lookups, destroyed;
static void lookup(const void *registry, void *output, const void *sprite) {
    assert(registry && sprite);
    ++lookups;
    new (output) std::string("fixture_registered_icon");
}
static void destroy(void *value) {
    static_cast<std::string *>(value)->~basic_string();
    ++destroyed;
}

static const char *data(const void *value) {
    return static_cast<const std::string *>(value)->data();
}

static size_t size(const void *value) {
    return static_cast<const std::string *>(value)->size();
}

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
    std::array<int, 2000> sources{};
    icon.hovered = nullptr;
    IconReferences snapshot(symbols);
    for (unsigned index = 0; index < sources.size(); ++index) {
        icon.normal = &sources[index];
        snapshot.collect(reinterpret_cast<void *>(1), node);
        assert(node.iconNormal == static_cast<int>(index));
    }
    icon.normal = &sources[0];
    snapshot.collect(reinterpret_cast<void *>(1), node);
    assert(node.iconNormal == 0);
    struct TestGlobal {
        unsigned char padding[47];
        void *registry;
    } global{};
    global.registry = &source;
    void *root = &global;
    symbols.address[Global] = reinterpret_cast<uintptr_t>(&root);
    symbols.address[StringData] = reinterpret_cast<uintptr_t>(data);
    symbols.address[StringSize] = reinterpret_cast<uintptr_t>(size);
    symbols.address[StringDestroy] = reinterpret_cast<uintptr_t>(destroy);
    symbols.iconNames = {reinterpret_cast<uintptr_t>(lookup), offsetof(TestGlobal, registry), sizeof(std::string)};
    ObservationStorage storage;
    icon.hovered = icon.normal;
    IconReferences named(symbols);
    named.collect(reinterpret_cast<void *>(1), node, &storage);
    named.collect(reinterpret_cast<void *>(1), node, &storage);
    assert(lookups == 1 && destroyed == 1);
    assert(observationText(node.iconNames[0]) == "fixture_registered_icon");
    assert(node.iconNames[0].address == node.iconNames[1].address);
    IconReferences fresh(symbols);
    icon.normal = &sources.back();
    fresh.collect(reinterpret_cast<void *>(1), node);
    assert(node.iconNormal == 0);
    layout.supported = 0;
    FmNode unavailable{};
    fresh.collect(reinterpret_cast<void *>(1), unavailable);
    assert(unavailable.properties == 0);
}
