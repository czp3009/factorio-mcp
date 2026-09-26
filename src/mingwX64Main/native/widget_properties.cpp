#include "widget_properties.h"
#include "protocol.h"
#include <algorithm>
#include <cstring>

namespace {
template <class T> T read(const void *object, unsigned offset) {
    T value;
    memcpy(&value, static_cast<const unsigned char *>(object) + offset, sizeof(value));
    return value;
}
} // namespace

SpriteSnapshot::SpriteSnapshot(const Symbols &symbols, FmResult &result) : symbols(symbols), result(result) {
    result.spriteCount = 0;
}

int SpriteSnapshot::observe(const void *sprite, unsigned depth) {
    if (!sprite)
        return -1;
    for (unsigned i = 0; i < result.spriteCount; ++i)
        if (sources[i] == sprite)
            return static_cast<int>(i);
    if (depth >= 16 || result.spriteCount == FM_MAX_SPRITES)
        return -2;
    const unsigned index = result.spriteCount++;
    sources[index] = sprite;
    auto &out = result.sprites[index];
    out = {};
    const auto &layout = symbols.sprites;
    const void *filename = read<const void *>(sprite, layout.filename);
    if (filename) {
        out.flags |= 1;
        const auto size = reinterpret_cast<size_t (*)(const void *)>(symbols.address[StringSize])(filename);
        const char *data = reinterpret_cast<const char *(*)(const void *)>(symbols.address[StringData])(filename);
        require(size <= 65536 && !memchr(data, 0, size), "Invalid sprite filename");
        size_t count = std::min(size, sizeof(out.filename) - 1);
        if (count != size) {
            out.flags |= 2;
            while (count && (static_cast<unsigned char>(data[count]) & 0xc0) == 0x80)
                --count;
        }
        memcpy(out.filename, data, count);
        out.filename[count] = 0;
    }
    if (read<bool>(sprite, layout.empty))
        out.flags |= 4;
    out.x = read<int16_t>(sprite, layout.x);
    out.y = read<int16_t>(sprite, layout.y);
    out.width = read<int16_t>(sprite, layout.width);
    out.height = read<int16_t>(sprite, layout.height);
    out.scale = read<double>(sprite, layout.scale);
    out.shiftX = read<double>(sprite, layout.shiftX);
    out.shiftY = read<double>(sprite, layout.shiftY);
    for (unsigned i = 0; i < 4; ++i)
        out.tint[i] = read<float>(sprite, layout.tint[i]);
    out.next = observe(read<const void *>(sprite, layout.next), depth + 1);
    out.extra = observe(read<const void *>(sprite, layout.extra), depth + 1);
    return static_cast<int>(index);
}

void SpriteSnapshot::collect(void *widget, FmNode &node) {
    const auto &layout = symbols.sprites;
    if (!layout.supported)
        return;
    auto *receiver = reinterpret_cast<void *(*)(void *, long, void *, void *, int)>(symbols.address[DynamicCast])(
        widget, 0, reinterpret_cast<void *>(symbols.address[WidgetType]), reinterpret_cast<void *>(layout.type), 0);
    if (!receiver)
        return;
    node.properties |= 2048;
    node.iconNormal = observe(read<const void *>(receiver, layout.normal), 0);
    node.iconHovered = observe(read<const void *>(receiver, layout.hovered), 0);
    node.iconDisabled = observe(read<const void *>(receiver, layout.disabled), 0);
}

void collectVisibility(const Symbols &symbols, void *widget, FmNode &node) {
    const auto &layout = symbols.visibility;
    if (!layout.supported)
        return;
    node.properties |= 128;
    const auto flags = read<uint32_t>(widget, layout.flags);
    node.visible = (flags & layout.visible) != 0;
    node.renderEnabled = (flags & layout.render) != 0;
    node.hiddenBySearch = (flags & layout.hiddenBySearch) != 0;
}

void collectElement(const Symbols &symbols, void *widget, FmNode &node) {
    const auto &layout = symbols.elements;
    if (!layout.supported)
        return;
    auto cast = [&](const void *value, uint64_t source, uint64_t target) {
        return reinterpret_cast<void *(*)(const void *, long, const void *, const void *, int)>(
            symbols.address[DynamicCast])(value, 0, reinterpret_cast<const void *>(source),
                                          reinterpret_cast<const void *>(target), 0);
    };
    auto get = [&](void *receiver, unsigned offset) {
        require(offset % sizeof(void *) == 0 && offset < 256 * sizeof(void *), "Invalid element method offset");
        const auto *table = read<const unsigned char *>(receiver, 0);
        const auto address = read<uintptr_t>(table, offset);
        require(address, "Missing element method");
        return reinterpret_cast<const void *(*)(const void *)>(address)(receiver);
    };
    const void *item = nullptr;
    if (auto *receiver = cast(widget, symbols.address[WidgetType], layout.stackProvider)) {
        node.properties |= 1024;
        node.elementFlags = 1;
        if (const auto *stack = get(receiver, layout.stackGetter)) {
            node.elementFlags |= 2;
            node.elementCount = read<uint32_t>(stack, layout.count);
            item = read<const void *>(stack, layout.stackItem);
        }
    } else if (auto *receiver = cast(widget, symbols.address[WidgetType], layout.itemProvider)) {
        node.properties |= 1024;
        item = get(receiver, layout.itemGetter);
        if (item)
            node.elementFlags |= 2;
    }
    if (!item)
        return;
    node.elementFlags |= 4;
    node.itemHealth = read<float>(item, layout.health);
    const auto *type = reinterpret_cast<const void *(*)(const void *)>(symbols.address[TypeId])(item);
    const char *name = reinterpret_cast<const char *(*)(const void *)>(symbols.address[TypeName])(type);
    size_t length = strlen(name);
    size_t count = std::min(length, sizeof(node.itemType) - 1);
    if (count < length) {
        node.elementFlags |= 32;
        while (count && (static_cast<unsigned char>(name[count]) & 0xc0) == 0x80)
            --count;
    }
    memcpy(node.itemType, name, count);
    node.itemType[count] = 0;
    if (auto *tool = cast(item, layout.itemType, layout.toolType)) {
        node.elementFlags |= 8;
        node.durabilityLeft = read<double>(tool, layout.durability);
    }
    if (auto *ammo = cast(item, layout.itemType, layout.ammoType)) {
        node.elementFlags |= 16;
        node.magazineLeft = read<float>(ammo, layout.magazine);
    }
}

void collectProgress(const Symbols &symbols, void *widget, FmNode &node) {
    const auto &layout = symbols.progress;
    if (!layout.supported)
        return;
    auto *receiver = reinterpret_cast<void *(*)(void *, long, void *, void *, int)>(symbols.address[DynamicCast])(
        widget, 0, reinterpret_cast<void *>(symbols.address[WidgetType]), reinterpret_cast<void *>(layout.type), 0);
    if (!receiver)
        return;
    node.properties |= 256;
    node.progressValue = read<double>(receiver, layout.value);
    node.progressDirection = read<uint8_t>(receiver, layout.direction);
    node.progressHasText = read<uint8_t>(receiver, layout.hasText);
}

void collectNumber(const Symbols &symbols, void *widget, FmNode &node) {
    const auto &layout = symbols.numbers;
    if (!layout.supported)
        return;
    auto *receiver = reinterpret_cast<void *(*)(void *, long, void *, void *, int)>(symbols.address[DynamicCast])(
        widget, 0, reinterpret_cast<void *>(symbols.address[WidgetType]), reinterpret_cast<void *>(layout.type), 0);
    if (!receiver)
        return;
    // The metadata reader accepts one owned vfptr and no bases. RTTI supplies the adjusted receiver.
    const auto *table = read<const unsigned char *>(receiver, 0);
    auto function = [&](unsigned offset) {
        require(offset % sizeof(void *) == 0 && offset < 256 * sizeof(void *), "Invalid number method offset");
        const auto address = read<uintptr_t>(table, offset);
        require(address, "Missing number method");
        return address;
    };
    auto flag = [&](unsigned offset) { return reinterpret_cast<bool (*)(const void *)>(function(offset))(receiver); };
    node.properties |= 64;
    if (!flag(layout.draw))
        return;
    node.numberFlags = 1;
    if (flag(layout.zero))
        node.numberFlags |= 2;
    if (flag(layout.unknown))
        node.numberFlags |= 4;
    if (flag(layout.infinite))
        node.numberFlags |= 8;
    if (!(node.numberFlags & 12)) {
        node.numberValue = reinterpret_cast<double (*)(const void *)>(function(layout.count))(receiver);
        node.numberFlags |= 16;
    }
}

void collectSwitch(const Symbols &symbols, void *widget, FmNode &node) {
    const auto &layout = symbols.switches;
    if (!layout.supported)
        return;
    auto *receiver = reinterpret_cast<void *(*)(void *, long, void *, void *, int)>(symbols.address[DynamicCast])(
        widget, 0, reinterpret_cast<void *>(symbols.address[WidgetType]), reinterpret_cast<void *>(layout.type), 0);
    if (!receiver)
        return;
    node.switchState = read<uint8_t>(receiver, layout.state);
    node.switchAllowNone = read<bool>(receiver, layout.allowNone);
    node.properties |= 8192;
}

void collectQualityCondition(const Symbols &symbols, void *widget, FmNode &node) {
    const auto &layout = symbols.conditions;
    if (!layout.supported)
        return;
    require(layout.size > 0 && layout.size <= 64 && layout.quality < layout.size && layout.comparison < layout.size,
            "Invalid quality condition return layout");
    require(layout.getter % sizeof(void *) == 0 && layout.getter < 256 * sizeof(void *),
            "Invalid quality condition method offset");
    auto *receiver = reinterpret_cast<void *(*)(void *, long, void *, void *, int)>(symbols.address[DynamicCast])(
        widget, 0, reinterpret_cast<void *>(symbols.address[WidgetType]), reinterpret_cast<void *>(layout.type), 0);
    if (!receiver)
        return;
    const auto *table = read<const unsigned char *>(receiver, 0);
    const auto address = read<uintptr_t>(table, layout.getter);
    require(address, "Missing quality condition method");
    alignas(16) unsigned char value[64]{};
    const auto *returned = reinterpret_cast<void *(*)(const void *, void *)>(address)(receiver, value);
    require(returned == value, "Unexpected quality condition return storage");
    node.conditionQuality = value[layout.quality];
    node.conditionComparison = value[layout.comparison];
    const auto *registry = reinterpret_cast<const void *>(layout.registry);
    require(registry, "Missing quality prototype registry");
    const auto first = read<uintptr_t>(registry, layout.first);
    const auto last = read<uintptr_t>(registry, layout.last);
    require(last >= first && (last - first) % sizeof(void *) == 0 && (last - first) / sizeof(void *) <= 65536 &&
                ((first == 0) == (last == 0)),
            "Invalid quality prototype registry");
    if (node.conditionQuality >= (last - first) / sizeof(void *)) {
        node.conditionLookup = 2;
    } else if (const auto *prototype = read<const unsigned char *>(reinterpret_cast<const void *>(first),
                                                                   node.conditionQuality * sizeof(void *))) {
        const auto *name = prototype + layout.name;
        const auto size = reinterpret_cast<size_t (*)(const void *)>(symbols.address[StringSize])(name);
        const auto *data = reinterpret_cast<const char *(*)(const void *)>(symbols.address[StringData])(name);
        require(size <= 65536 && !memchr(data, 0, size), "Invalid quality condition name");
        size_t count = std::min(size, sizeof(node.conditionName) - 1);
        if (count < size) {
            node.conditionNameTruncated = 1;
            while (count && (static_cast<unsigned char>(data[count]) & 0xc0) == 0x80)
                --count;
        }
        memcpy(node.conditionName, data, count);
        node.conditionName[count] = 0;
        node.conditionLookup = 1;
    }
    node.properties |= 4096;
}

void collectSlotIdentity(const Symbols &symbols, void *widget, FmNode &node) {
    const auto &layout = symbols.slots;
    if (!layout.supported)
        return;
    auto *receiver = reinterpret_cast<void *(*)(void *, long, void *, void *, int)>(symbols.address[DynamicCast])(
        widget, 0, reinterpret_cast<void *>(symbols.address[WidgetType]), reinterpret_cast<void *>(layout.type), 0);
    if (!receiver)
        return;
    const auto *table = read<const unsigned char *>(receiver, 0);
    auto get = [&](unsigned offset) {
        require(offset % sizeof(void *) == 0 && offset < 256 * sizeof(void *), "Invalid prototype method offset");
        const auto address = read<uintptr_t>(table, offset);
        require(address, "Missing prototype method");
        return reinterpret_cast<const unsigned char *(*)(const void *)>(address)(receiver);
    };
    auto copy = [&](const char *data, size_t size, char *out, size_t capacity, unsigned flag) {
        require(size <= 65536 && !memchr(data, 0, size), "Invalid UI prototype identity string");
        size_t count = std::min(size, capacity - 1);
        if (count < size) {
            node.identityTruncated |= flag;
            while (count && (static_cast<unsigned char>(data[count]) & 0xc0) == 0x80)
                --count;
        }
        memcpy(out, data, count);
        out[count] = 0;
    };
    auto observe = [&](const unsigned char *prototype, unsigned nameOffset, char *nameOut, size_t nameCapacity,
                       char *typeOut, size_t typeCapacity, unsigned flag) {
        if (!prototype)
            return;
        const void *name = prototype + nameOffset;
        const auto size = reinterpret_cast<size_t (*)(const void *)>(symbols.address[StringSize])(name);
        const char *data = reinterpret_cast<const char *(*)(const void *)>(symbols.address[StringData])(name);
        copy(data, size, nameOut, nameCapacity, flag);
        const void *type = reinterpret_cast<const void *(*)(const void *)>(symbols.address[TypeId])(prototype);
        const char *typeName = reinterpret_cast<const char *(*)(const void *)>(symbols.address[TypeName])(type);
        copy(typeName, strlen(typeName), typeOut, typeCapacity, flag << 1);
    };
    node.properties |= 32 | 512;
    observe(get(layout.prototype), layout.name, node.prototypeName, sizeof(node.prototypeName), node.prototypeType,
            sizeof(node.prototypeType), 1);
    observe(get(layout.quality), layout.qualityName, node.qualityName, sizeof(node.qualityName), node.qualityType,
            sizeof(node.qualityType), 4);
}

void collectWidgetProperties(const Symbols &symbols, void *widget, FmNode &node, FmResult *result) {
    const auto &layout = symbols.properties;
    if (!layout.supported)
        return;
    auto cast = [&](FmSymbol type) {
        return reinterpret_cast<void *(*)(void *, long, void *, void *, int)>(symbols.address[DynamicCast])(
            widget, 0, reinterpret_cast<void *>(symbols.address[WidgetType]),
            reinterpret_cast<void *>(symbols.address[type]), 0);
    };
    if (auto *value = cast(ToggleButtonType)) {
        node.properties |= 1;
        node.checkState = read<int32_t>(value, layout.checkState);
    }
    if (auto *value = cast(ButtonType); value && read<bool>(value, layout.toggleMode)) {
        node.properties |= 2;
        node.toggled = read<bool>(value, layout.toggled);
    }
    if (auto *value = cast(DropDownType)) {
        node.properties |= 4;
        node.selectedIndex = read<int32_t>(value, layout.selectedIndex);
        if (layout.optionsSupported && result) {
            const auto first = read<uintptr_t>(value, layout.optionFirst);
            const auto last = read<uintptr_t>(value, layout.optionLast);
            require(layout.optionStride && last >= first && (last - first) % layout.optionStride == 0 &&
                        (last - first) / layout.optionStride <= 65536 && (first || last == first),
                    "Invalid dropdown option storage");
            require(result->optionCount <= FM_MAX_OPTIONS, "Invalid option snapshot capacity");
            node.properties |= 16;
            node.optionTotal = static_cast<uint32_t>((last - first) / layout.optionStride);
            node.optionFirst = result->optionCount;
            node.optionCount = std::min(
                {node.optionTotal, uint32_t(FM_MAX_WIDGET_OPTIONS), uint32_t(FM_MAX_OPTIONS) - result->optionCount});
            for (uint32_t i = 0; i < node.optionCount; ++i) {
                const auto *entry = reinterpret_cast<const void *>(first + size_t(i) * layout.optionStride);
                const auto *button = read<const unsigned char *>(entry, layout.optionButton);
                require(button, "Dropdown option has no text button");
                const void *text = button + layout.optionText;
                const size_t length = reinterpret_cast<size_t (*)(const void *)>(symbols.address[StringSize])(text);
                const char *data = reinterpret_cast<const char *(*)(const void *)>(symbols.address[StringData])(text);
                auto &option = result->options[result->optionCount++];
                size_t count = std::min(length, sizeof(option.text) - 1);
                option.truncated = count != length;
                if (option.truncated)
                    while (count && (static_cast<unsigned char>(data[count]) & 0xc0) == 0x80)
                        --count;
                memcpy(option.text, data, count);
                option.text[count] = 0;
            }
        }
    }
    if (auto *value = cast(SliderType)) {
        node.properties |= 8;
        node.value = read<double>(value, layout.value);
        node.minimum = read<double>(value, layout.minimum);
        node.maximum = read<double>(value, layout.maximum);
        node.valueStep = read<double>(value, layout.valueStep);
    }
}
