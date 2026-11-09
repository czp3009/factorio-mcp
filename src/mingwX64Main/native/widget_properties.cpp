#include "widget_properties.h"
#include "protocol.h"
#include <algorithm>
#include <cstring>
#include <limits>

namespace {
template <class T> T read(const void *object, unsigned offset) {
    T value;
    memcpy(&value, static_cast<const unsigned char *>(object) + offset, sizeof(value));
    return value;
}
} // namespace

const void *readWidgetText(const Symbols &symbols, void *widget) {
    auto cast = [&](FmSymbol type) {
        return reinterpret_cast<void *(*)(void *, long, void *, void *, int)>(symbols.address[DynamicCast])(
            widget, 0, reinterpret_cast<void *>(symbols.address[WidgetType]),
            reinterpret_cast<void *>(symbols.address[type]), 0);
    };
    auto getter = [&](FmSymbol method, void *receiver) {
        return reinterpret_cast<const void *(*)(void *)>(symbols.address[method])(receiver);
    };
    if (auto *receiver = cast(TextBoxType))
        return getter(TextBoxText, receiver);
    if (auto *receiver = cast(LabelType))
        return getter(LabelText, receiver);
    return getter(Text, widget);
}

void collectTooltip(const Symbols &symbols, void *widget, FmNode &node, ObservationStorage &storage) {
    const auto &layout = symbols.tooltips;
    if (!layout.plainType)
        return;
    auto *creator = read<void *>(widget, layout.creator);
    if (!creator)
        return;
    node.properties |= 16384;
    node.tooltipStatus = 2;
    auto *plain = reinterpret_cast<void *(*)(void *, long, void *, void *, int)>(symbols.address[DynamicCast])(
        creator, 0, reinterpret_cast<void *>(layout.baseType), reinterpret_cast<void *>(layout.plainType), 0);
    if (!plain)
        return;
    auto copy = [&](uint32_t offset, FmObservationBuffer &output, uint32_t flag) {
        const void *text = static_cast<const unsigned char *>(plain) + offset;
        const auto size = reinterpret_cast<size_t (*)(const void *)>(symbols.address[StringSize])(text);
        const auto *data = reinterpret_cast<const char *(*)(const void *)>(symbols.address[StringData])(text);
        bool truncated = false;
        output = storage.copyText(data, size, truncated);
        if (truncated)
            node.tooltipTruncated |= flag;
    };
    copy(layout.title, node.tooltipTitle, 1);
    copy(layout.text, node.tooltipText, 2);
    node.tooltipStatus = 3;
}

IconReferences::IconReferences(const Symbols &symbols) : symbols(symbols) {}

int IconReferences::observe(const void *sprite) {
    if (!sprite)
        return -1;
    const auto found = sources.find(sprite);
    if (found != sources.end())
        return found->second;
    require(sources.size() < std::numeric_limits<int>::max(), "Icon references exceed integer representation");
    const int index = static_cast<int>(sources.size());
    sources.emplace(sprite, index);
    return index;
}

void IconReferences::collect(void *widget, FmNode &node, ObservationStorage *storage) {
    const auto &layout = symbols.icons;
    if (!layout.supported)
        return;
    auto *receiver = reinterpret_cast<void *(*)(void *, long, void *, void *, int)>(symbols.address[DynamicCast])(
        widget, 0, reinterpret_cast<void *>(symbols.address[WidgetType]), reinterpret_cast<void *>(layout.type), 0);
    if (!receiver)
        return;
    node.properties |= 2048;
    node.iconNormal = observe(read<const void *>(receiver, layout.normal));
    node.iconHovered = observe(read<const void *>(receiver, layout.hovered));
    node.iconDisabled = observe(read<const void *>(receiver, layout.disabled));
    const uint32_t offsets[] = {layout.normal, layout.hovered, layout.disabled};
    for (size_t i = 0; i < 3; ++i) {
        const auto *sprite = read<const void *>(receiver, offsets[i]);
        const auto copied = name(sprite, storage);
        node.iconNames[i] = copied.first;
        if (copied.second)
            node.iconNameTruncated |= 1u << i;
    }
}

std::pair<FmObservationBuffer, bool> IconReferences::name(const void *sprite, ObservationStorage *storage) {
    const auto &layout = symbols.iconNames;
    if (!sprite || !layout.lookup || !storage)
        return {};
    if (const auto found = names.find(sprite); found != names.end())
        return found->second;
    const auto *global = *reinterpret_cast<const void **>(symbols.address[Global]);
    const auto *registry = global ? read<const void *>(global, layout.registry) : nullptr;
    if (!registry)
        return {};
    require(layout.stringSize && layout.stringSize <= 256, "Invalid sprite-name storage");
    alignas(16) unsigned char result[256]{};
    reinterpret_cast<void (*)(const void *, void *, const void *)>(layout.lookup)(registry, result, sprite);
    auto destroy = reinterpret_cast<void (*)(void *)>(symbols.address[StringDestroy]);
    std::pair<FmObservationBuffer, bool> copied{};
    try {
        auto size = reinterpret_cast<size_t (*)(const void *)>(symbols.address[StringSize])(result);
        auto *data = reinterpret_cast<const char *(*)(const void *)>(symbols.address[StringData])(result);
        copied.first = storage->copyText(data, size, copied.second);
    } catch (...) {
        destroy(result);
        throw;
    }
    destroy(result);
    names.emplace(sprite, copied);
    return copied;
}

void IconReferences::collectImage(void *widget, FmNode &node, ObservationStorage &storage) {
    const auto &layout = symbols.images;
    require(validUiImageLayout(layout), "Invalid image metadata layout");
    if (!layout.supported)
        return;
    auto cast = [&](void *object, uint64_t source, uint64_t target) {
        return reinterpret_cast<void *(*)(void *, long, void *, void *, int)>(symbols.address[DynamicCast])(
            object, 0, reinterpret_cast<void *>(source), reinterpret_cast<void *>(target), 0);
    };
    auto *receiver = cast(widget, symbols.address[WidgetType], layout.type);
    if (!receiver)
        return;
    node.properties |= 32768;
    auto &output = node.image;
    output.status = 1;
    auto *image = read<void *>(receiver, layout.image);
    if (!image)
        return;
    output.status = 2;
    const auto *type = reinterpret_cast<const void *(*)(const void *)>(symbols.address[TypeId])(image);
    const auto *typeName = reinterpret_cast<const char *(*)(const void *)>(symbols.address[TypeName])(type);
    bool truncated = false;
    const auto copiedType = storage.copyText(typeName, strlen(typeName), truncated);
    output.type = {copiedType.address, copiedType.size};
    if (const auto *plain = cast(image, layout.imageType, layout.staticType)) {
        // Observe the stored static sprite, never an animation's selected frame.
        const auto *sprite = read<const void *>(plain, layout.sprite);
        output.spriteObserved = 1;
        output.reference = observe(sprite);
        const auto copiedName = name(sprite, &storage);
        output.name = {copiedName.first.address, copiedName.first.size};
        output.nameTruncated = copiedName.second;
    }
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

void collectElement(const Symbols &symbols, void *widget, FmNode &node, ObservationStorage &storage) {
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
    bool truncated = false;
    node.itemType = storage.copyText(name, strlen(name), truncated);
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

void collectAchievement(const Symbols &symbols, void *widget, FmNode &node) {
    const auto &layout = symbols.achievement;
    require(fm::validAchievementLayout(layout), "Invalid achievement card layout");
    if (!layout.supported) return;
    auto *receiver = reinterpret_cast<void *(*)(void *, long, void *, void *, int)>(symbols.address[DynamicCast])(
        widget, 0, reinterpret_cast<void *>(symbols.address[WidgetType]), reinterpret_cast<void *>(layout.type), 0);
    if (!receiver) return;
    node.achievement.available = 1;
    node.achievement.state = read<int32_t>(receiver, layout.state);
    node.achievement.shownState = read<int32_t>(receiver, layout.shownState);
    node.achievement.inSidebar = read<uint8_t>(receiver, layout.inSidebar);
    require(node.achievement.inSidebar <= 1, "Invalid achievement sidebar flag");
}

void collectGraph(const Symbols &symbols, void *widget, FmNode &node, ObservationStorage &storage,
                  const FmUiGraphRequest &request, const volatile LONG *cancel) {
    const auto &layout = symbols.graph;
    require(fm::validGraphLayout(layout), "Invalid graph metadata layout");
    if (!layout.supported)
        return;
    auto *receiver = reinterpret_cast<void *(*)(void *, long, void *, void *, int)>(symbols.address[DynamicCast])(
        widget, 0, reinterpret_cast<void *>(symbols.address[WidgetType]), reinterpret_cast<void *>(layout.type), 0);
    if (!receiver)
        return;
    auto string = [&](uintptr_t at) {
        const auto *object = reinterpret_cast<const void *>(at);
        const auto size = reinterpret_cast<size_t (*)(const void *)>(symbols.address[StringSize])(object);
        require(size <= INT32_MAX, "Graph text exceeds byte-array representation");
        const auto *data = reinterpret_cast<const char *(*)(const void *)>(symbols.address[StringData])(object);
        return std::string(data, size);
    };
    node.graph = storage.graphs.emplace_back().collect(reinterpret_cast<uintptr_t>(receiver), layout, request,
        [](uintptr_t at, void *out, size_t count) {
            SIZE_T read = 0;
            return ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<const void *>(at), out, count, &read) && read == count;
        }, string, [&] { require(!cancel || !*cancel, "Tool cancelled during graph observation"); });
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

void collectSlotIdentity(const Symbols &symbols, void *widget, FmNode &node, ObservationStorage &storage,
                         uint32_t fields) {
    if (fields & FM_UI_PROTOTYPE) {
        const FmUiStoredPrototypeLayout *layouts[] = {&symbols.connectionPrototype, &symbols.achievementPrototype};
        for (const auto *stored : layouts) {
            require(fm::validStoredPrototypeLayout(*stored), "Invalid stored UI prototype layout");
            if (!stored->supported || (node.properties & 32u)) continue;
            auto *receiver = reinterpret_cast<void *(*)(void *, long, void *, void *, int)>(symbols.address[DynamicCast])(
                widget, 0, reinterpret_cast<void *>(symbols.address[WidgetType]), reinterpret_cast<void *>(stored->type), 0);
            if (!receiver) continue;
            const auto prototype = fm::storedPrototype(reinterpret_cast<uintptr_t>(receiver), *stored,
                [](uintptr_t at, void *out, size_t size) {
                    SIZE_T copied = 0;
                    return ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<const void *>(at), out, size, &copied) && copied == size;
                });
            node.properties |= 32u;
            if (!prototype) continue;
            const auto *name = reinterpret_cast<const void *>(prototype + stored->name);
            const auto size = reinterpret_cast<size_t (*)(const void *)>(symbols.address[StringSize])(name);
            const auto *data = reinterpret_cast<const char *(*)(const void *)>(symbols.address[StringData])(name);
            require(size <= 65536 && !memchr(data, 0, size), "Invalid stored UI prototype name");
            size_t count = std::min(size, sizeof(node.prototypeName) - 1);
            if (count < size) {
                node.identityTruncated |= 1u;
                while (count && (static_cast<unsigned char>(data[count]) & 0xc0) == 0x80) --count;
            }
            memcpy(node.prototypeName, data, count);
            node.prototypeName[count] = 0;
            const auto *type = reinterpret_cast<const void *(*)(const void *)>(symbols.address[TypeId])(reinterpret_cast<const void *>(prototype));
            const auto *typeName = reinterpret_cast<const char *(*)(const void *)>(symbols.address[TypeName])(type);
            bool truncated = false;
            node.prototypeType = storage.copyText(typeName, strlen(typeName), truncated);
        }
    }
    const auto &layout = symbols.slots;
    if (!layout.supported || !(fields & (FM_UI_PROTOTYPE | FM_UI_QUALITY)))
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
                       FmObservationBuffer &typeOut, unsigned flag) {
        if (!prototype)
            return;
        const void *name = prototype + nameOffset;
        const auto size = reinterpret_cast<size_t (*)(const void *)>(symbols.address[StringSize])(name);
        const char *data = reinterpret_cast<const char *(*)(const void *)>(symbols.address[StringData])(name);
        copy(data, size, nameOut, nameCapacity, flag);
        const void *type = reinterpret_cast<const void *(*)(const void *)>(symbols.address[TypeId])(prototype);
        const char *typeName = reinterpret_cast<const char *(*)(const void *)>(symbols.address[TypeName])(type);
        bool truncated = false;
        typeOut = storage.copyText(typeName, strlen(typeName), truncated);
    };
    if ((fields & FM_UI_PROTOTYPE) && !(node.properties & 32)) {
        node.properties |= 32;
        observe(get(layout.prototype), layout.name, node.prototypeName, sizeof(node.prototypeName), node.prototypeType, 1);
    }
    if ((fields & FM_UI_QUALITY) && !(node.properties & 512)) {
        node.properties |= 512;
        observe(get(layout.quality), layout.qualityName, node.qualityName, sizeof(node.qualityName), node.qualityType, 4);
    }
}

void collectWidgetProperties(const Symbols &symbols, void *widget, FmNode &node, FmResult *result,
                             ObservationStorage *storage, const volatile LONG *cancel, uint32_t fields) {
    const auto &layout = symbols.properties;
    if (!layout.supported)
        return;
    auto cast = [&](FmSymbol type) {
        return reinterpret_cast<void *(*)(void *, long, void *, void *, int)>(symbols.address[DynamicCast])(
            widget, 0, reinterpret_cast<void *>(symbols.address[WidgetType]),
            reinterpret_cast<void *>(symbols.address[type]), 0);
    };
    if (auto *value = fields & FM_UI_CHECK_STATE ? cast(ToggleButtonType) : nullptr) {
        node.properties |= 1;
        node.checkState = read<int32_t>(value, layout.checkState);
    }
    if (auto *value = fields & (FM_UI_TOGGLED | FM_UI_TOGGLE_MODE) ? cast(ButtonType) : nullptr) {
        node.properties |= 2;
        node.toggleMode = read<bool>(value, layout.toggleMode);
        node.toggled = read<bool>(value, layout.toggled);
    }
    if (auto *value = fields & (FM_UI_SELECTED_INDEX | FM_UI_OPTIONS) ? cast(DropDownType) : nullptr) {
        if (fields & FM_UI_SELECTED_INDEX) {
            node.properties |= 4;
            node.selectedIndex = read<int32_t>(value, layout.selectedIndex);
        }
        if ((fields & FM_UI_OPTIONS) && layout.optionsSupported && result) {
            require(storage, "Option observation storage is absent");
            const auto first = read<uintptr_t>(value, layout.optionFirst);
            const auto last = read<uintptr_t>(value, layout.optionLast);
            require(layout.optionStride && last >= first && (last - first) % layout.optionStride == 0 &&
                        (last - first) / layout.optionStride <= std::numeric_limits<int>::max() &&
                        (first || last == first),
                    "Invalid dropdown option storage");
            require(result->optionCount == storage->options.size(), "Invalid option snapshot count");
            node.properties |= 16;
            node.optionTotal = static_cast<uint32_t>((last - first) / layout.optionStride);
            node.optionFirst = result->optionCount;
            node.optionOffset = storage->optionOffset;
            node.optionCount = std::min(storage->optionLimit, node.optionTotal - std::min(node.optionTotal, node.optionOffset));
            require(node.optionCount <= uint32_t(std::numeric_limits<int>::max()) - result->optionCount,
                    "Option observations exceed integer representation");
            for (uint32_t i = 0; i < node.optionCount; ++i) {
                require(!cancel || !*cancel, "UI option query cancelled");
                const auto *entry = reinterpret_cast<const void *>(first + size_t(i + node.optionOffset) * layout.optionStride);
                const auto *button = read<const unsigned char *>(entry, layout.optionButton);
                require(button, "Dropdown option has no text button");
                const void *text = button + layout.optionText;
                const size_t length = reinterpret_cast<size_t (*)(const void *)>(symbols.address[StringSize])(text);
                const char *data = reinterpret_cast<const char *(*)(const void *)>(symbols.address[StringData])(text);
                auto &option = storage->options.emplace_back();
                bool truncated = false;
                option.text = storage->copyText(data, length, truncated);
                option.truncated = truncated;
                ++result->optionCount;
            }
        }
    }
    if (auto *value = fields & FM_UI_SLIDER ? cast(SliderType) : nullptr) {
        node.properties |= 8;
        node.value = read<double>(value, layout.value);
        node.minimum = read<double>(value, layout.minimum);
        node.maximum = read<double>(value, layout.maximum);
        node.valueStep = read<double>(value, layout.valueStep);
    }
}
