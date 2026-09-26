#include "widget_properties.h"
#include <array>
#include <cassert>
#include <cmath>
#include <cstddef>
#include <limits>
#include <stdexcept>

namespace {
struct Item {
    float health;
} item{};

struct Stack {
    char padding[11];
    uint32_t count;
    Item *item;
} stack{};

struct Tool {
    char padding[17];
    double durability;
} tool{};

struct Ammo {
    char padding[7];
    float magazine;
} ammo{};

struct Receiver {
    uintptr_t *table;
} stackReceiver{}, itemReceiver{};

int widget, widgetType, stackType, providerType, itemType, toolType, ammoType;
bool supported = true, stackMode = true, present = true, isTool = false, isAmmo = false;

void *cast(const void *value, long, const void *source, const void *target, int) {
    if (value == &widget) {
        assert(source == &widgetType);
        if (!supported)
            return nullptr;
        if (target == &stackType)
            return stackMode ? &stackReceiver : nullptr;
        assert(target == &providerType);
        return stackMode ? nullptr : &itemReceiver;
    }
    assert(value == &item && source == &itemType);
    if (target == &toolType)
        return isTool ? &tool : nullptr;
    assert(target == &ammoType);
    return isAmmo ? &ammo : nullptr;
}

const void *getStack(const void *receiver) {
    assert(receiver == &stackReceiver);
    return present ? &stack : nullptr;
}

const void *getItem(const void *receiver) {
    assert(receiver == &itemReceiver);
    return present ? &item : nullptr;
}

const void *typeId(const void *value) {
    assert(value == &item);
    return value;
}

const char *typeName(const void *value) {
    assert(value == &item);
    return "class FixtureItem";
}
} // namespace

int main() {
    Symbols symbols{};
    symbols.address[DynamicCast] = reinterpret_cast<uintptr_t>(&cast);
    symbols.address[WidgetType] = reinterpret_cast<uintptr_t>(&widgetType);
    symbols.address[TypeId] = reinterpret_cast<uintptr_t>(&typeId);
    symbols.address[TypeName] = reinterpret_cast<uintptr_t>(&typeName);
    auto &layout = symbols.elements;
    layout.stackProvider = reinterpret_cast<uintptr_t>(&stackType);
    layout.itemProvider = reinterpret_cast<uintptr_t>(&providerType);
    layout.itemType = reinterpret_cast<uintptr_t>(&itemType);
    layout.toolType = reinterpret_cast<uintptr_t>(&toolType);
    layout.ammoType = reinterpret_cast<uintptr_t>(&ammoType);
    layout.stackItem = offsetof(Stack, item);
    layout.count = offsetof(Stack, count);
    layout.health = offsetof(Item, health);
    layout.durability = offsetof(Tool, durability);
    layout.magazine = offsetof(Ammo, magazine);
    auto collect = [&] {
        FmNode node{};
        collectElement(symbols, &widget, node);
        return node;
    };
    assert(collect().properties == 0);
    layout.supported = 1;
    std::array<uintptr_t, 10> table{};
    stackReceiver.table = itemReceiver.table = table.data();
    for (unsigned shift : {0u, 3u}) {
        layout.stackGetter = (shift + 2) * sizeof(void *);
        layout.itemGetter = (shift + 4) * sizeof(void *);
        table[shift + 2] = reinterpret_cast<uintptr_t>(&getStack);
        table[shift + 4] = reinterpret_cast<uintptr_t>(&getItem);
        stackMode = true;
        present = false;
        auto node = collect();
        assert(node.properties == 1024 && node.elementFlags == 1);
        present = true;
        stack.count = UINT32_MAX;
        stack.item = nullptr;
        node = collect();
        assert(node.elementFlags == 3 && node.elementCount == UINT32_MAX);
        stack.item = &item;
        item.health = 0.375f;
        isTool = true;
        isAmmo = false;
        tool.durability = -0.25;
        node = collect();
        assert(node.elementFlags == 15 && node.itemHealth == 0.375f && node.durabilityLeft == -0.25);
        stackMode = false;
        isTool = false;
        isAmmo = true;
        ammo.magazine = 3.5f;
        item.health = std::numeric_limits<float>::quiet_NaN();
        node = collect();
        assert(node.elementFlags == 22 && std::isnan(node.itemHealth) && node.magazineLeft == 3.5f);
        present = false;
        assert(collect().elementFlags == 0 && collect().properties == 1024);
    }
    supported = false;
    assert(collect().properties == 0);
    supported = present = true;
    layout.itemGetter = 3;
    bool rejected = false;
    try {
        collect();
    } catch (const std::runtime_error &) {
        rejected = true;
    }
    assert(rejected);
}
