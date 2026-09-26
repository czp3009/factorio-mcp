#include "widget_properties.h"
#include <cassert>
#include <cstddef>
#include <memory>
#include <string>
#include <limits>

struct Sprite {
    void *padding{};
    Sprite *extra{}, *next{};
    double shiftY{}, scale{1}, shiftX{};
    bool empty{};
    float tint[4]{1, 0.5f, 0.25f, 1};
    const std::string *filename{};
    int16_t height{32}, width{64}, y{9}, x{-7};
};

struct Icon {
    void *padding{};
    Sprite *disabled{}, *normal{}, *hovered{};
};

static Icon *receiver{};

static void *cast(void *widget, long, void *, void *, int) {
    return widget == reinterpret_cast<void *>(1) ? receiver : nullptr;
}

static const char *data(const std::string *text) {
    return text->data();
}

static size_t size(const std::string *text) {
    return text->size();
}

int main() {
    Symbols symbols{};
    symbols.address[DynamicCast] = reinterpret_cast<uint64_t>(cast);
    symbols.address[StringData] = reinterpret_cast<uint64_t>(data);
    symbols.address[StringSize] = reinterpret_cast<uint64_t>(size);
    auto &layout = symbols.sprites;
    layout.supported = 1;
    layout.normal = offsetof(Icon, normal);
    layout.hovered = offsetof(Icon, hovered);
    layout.disabled = offsetof(Icon, disabled);
    layout.filename = offsetof(Sprite, filename);
    layout.x = offsetof(Sprite, x);
    layout.y = offsetof(Sprite, y);
    layout.width = offsetof(Sprite, width);
    layout.height = offsetof(Sprite, height);
    layout.scale = offsetof(Sprite, scale);
    layout.shiftX = offsetof(Sprite, shiftX);
    layout.shiftY = offsetof(Sprite, shiftY);
    layout.empty = offsetof(Sprite, empty);
    layout.next = offsetof(Sprite, next);
    layout.extra = offsetof(Sprite, extra);
    for (unsigned i = 0; i < 4; ++i)
        layout.tint[i] = offsetof(Sprite, tint) + i * sizeof(float);
    auto result = std::make_unique<FmResult>();
    SpriteSnapshot snapshot(symbols, *result);
    FmNode node{};
    std::string filename = "__test__/icon.png";
    Sprite first, second;
    first.filename = &filename;
    first.extra = &second;
    second.next = &first;
    second.empty = true;
    second.scale = -0.5;
    second.tint[0] = std::numeric_limits<float>::quiet_NaN();
    Icon icon;
    receiver = &icon;
    icon.normal = &first;
    icon.hovered = &first;
    snapshot.collect(reinterpret_cast<void *>(1), node);
    assert(node.properties == 2048 && node.iconNormal == 0 && node.iconHovered == 0 && node.iconDisabled == -1);
    assert(result->spriteCount == 2);
    assert(std::string(result->sprites[0].filename) == filename);
    assert(result->sprites[0].x == -7 && result->sprites[0].width == 64 && result->sprites[0].extra == 1);
    assert(result->sprites[1].next == 0 && result->sprites[1].flags == 4 && result->sprites[1].scale == -0.5);
    FmNode unsupported{};
    snapshot.collect(nullptr, unsupported);
    assert(unsupported.properties == 0);
    {
        SpriteSnapshot fresh(symbols, *result);
        std::array<Sprite, 17> chain;
        for (unsigned i = 0; i + 1 < chain.size(); ++i)
            chain[i].next = &chain[i + 1];
        icon.normal = &chain[0];
        icon.hovered = nullptr;
        fresh.collect(reinterpret_cast<void *>(1), node);
        assert(result->spriteCount == 16 && result->sprites[15].next == -2);
    }
    {
        SpriteSnapshot fresh(symbols, *result);
        std::array<Sprite, FM_MAX_SPRITES + 1> many;
        std::string longName(510, 'x');
        longName += "\xc3\xa9";
        many[0].filename = &longName;
        for (auto &sprite : many) {
            icon.normal = &sprite;
            fresh.collect(reinterpret_cast<void *>(1), node);
        }
        assert(result->spriteCount == FM_MAX_SPRITES && node.iconNormal == -2);
        assert(result->sprites[0].flags == 3 && std::string(result->sprites[0].filename).size() == 510);
        icon.normal = &many[0];
        fresh.collect(reinterpret_cast<void *>(1), node);
        assert(node.iconNormal == 0);
    }
}
