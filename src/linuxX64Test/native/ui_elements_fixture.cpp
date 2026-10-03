#include "ui_elements.h"
#include <cassert>
#include <cerrno>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <typeinfo>

struct Widget {
    virtual ~Widget() = default;
    char padding[FIXTURE_PADDING + 1]{};
};

struct OtherBase {
    virtual ~OtherBase() = default;
    uintptr_t padding[3]{};
};

struct Item {
    virtual ~Item() = default;
    char padding[FIXTURE_PADDING + 3]{};
    float health = 0.625f;
};

struct Tool : OtherBase, Item {
    double durability = 91.75;
};

struct Ammo : Item, OtherBase {
    float magazine = 4.5f;
};

struct Stack {
    char padding[FIXTURE_PADDING + 2]{};
    Item *item = nullptr;
    uint32_t count = 0;
};

template <class T> struct Provider {
    virtual uintptr_t unrelated() const { return 0; }
    virtual const T *get() const = 0;
};

template <class T> struct ConcreteProvider : Provider<T> {
    const T *value = nullptr;
    bool fail = false;
    mutable unsigned calls = 0;

    const T *get() const override {
        ++calls;
        if (fail)
            throw std::runtime_error("fixture");
        return value;
    }
};

struct StackButton : OtherBase, virtual Widget, ConcreteProvider<Stack> {};
struct ItemButton : OtherBase, Widget, ConcreteProvider<Item> {};
struct BothButton : Widget, ConcreteProvider<Stack>, ConcreteProvider<Item> {};

template <class Owner, class Value> uint32_t offset(const Owner &owner, const Value &value) {
    return reinterpret_cast<uintptr_t>(&value) - reinterpret_cast<uintptr_t>(&owner);
}

static FmLinuxElementLayout layout() {
    Item item;
    Tool tool;
    Ammo ammo;
    Stack stack;
    FmLinuxElementLayout result{};
    result.widgetType = reinterpret_cast<uintptr_t>(&typeid(Widget));
    result.stackProvider = reinterpret_cast<uintptr_t>(&typeid(Provider<Stack>));
    result.itemProvider = reinterpret_cast<uintptr_t>(&typeid(Provider<Item>));
    result.itemType = reinterpret_cast<uintptr_t>(&typeid(Item));
    result.toolType = reinterpret_cast<uintptr_t>(&typeid(Tool));
    result.ammoType = reinterpret_cast<uintptr_t>(&typeid(Ammo));
    // These offsets and reordered slots belong only to synthetic fixture types.
    result.stackGetter = result.itemGetter = 1;
    result.stackExtent = sizeof(Stack);
    result.itemSize = sizeof(Item);
    result.toolSize = sizeof(Tool);
    result.ammoSize = sizeof(Ammo);
    result.stackItem = offset(stack, stack.item);
    result.count = offset(stack, stack.count);
    result.health = offset(item, item.health);
    result.durability = offset(tool, tool.durability);
    result.magazine = offset(ammo, ammo.magazine);
    assert(offset(tool, static_cast<Item &>(tool)) > 0);
    return result;
}

template <class T> int observe(T &widget, FmLinuxUiElement &output) {
    return collectElement(reinterpret_cast<uintptr_t>(static_cast<Widget *>(&widget)), layout(), output);
}

int main() {
    assert(validElementLayout(layout()));
    FmLinuxUiElement output{};
    Widget ordinary;
    assert(observe(ordinary, output) == 0 && !output.available);
    StackButton stackButton;
    Stack stack;
    assert(observe(stackButton, output) == 0 && output.available && output.flags == 1);
    stackButton.value = &stack;
    stack.count = 3000000000u;
    assert(observe(stackButton, output) == 0 && output.flags == 3 && output.count == stack.count);
    assert(!stack.item); // Reading a populated stack must not construct its optional Item.
    stack.count = 0;
    assert(observe(stackButton, output) == 0 && output.flags == 3 && output.count == 0);
    Item item;
    stack.item = &item;
    assert(observe(stackButton, output) == 0 && output.flags == 7 && output.health == item.health);
    assert(std::string(output.type, output.typeSize) == "Item");
    Tool tool;
    stack.item = &tool;
    assert(observe(stackButton, output) == 0 && output.flags == 15 && output.health == tool.health &&
           output.durability == tool.durability);
    assert(std::string(output.type, output.typeSize) == "Tool");
    Ammo ammo;
    stack.item = &ammo;
    assert(observe(stackButton, output) == 0 && output.flags == 23 && output.magazine == ammo.magazine);
    ItemButton itemButton;
    assert(observe(itemButton, output) == 0 && output.available && !output.flags);
    itemButton.value = &tool;
    assert(observe(itemButton, output) == 0 && output.flags == 14 && output.durability == tool.durability);
    BothButton both;
    both.ConcreteProvider<Stack>::value = &stack;
    both.ConcreteProvider<Item>::value = &tool;
    assert(observe(both, output) == 0 && output.flags == 23 && both.ConcreteProvider<Stack>::calls == 1 &&
           !both.ConcreteProvider<Item>::calls);
    stack.item = reinterpret_cast<Item *>(1);
    assert(observe(stackButton, output) == EFAULT);
    stackButton.value = reinterpret_cast<Stack *>(1);
    assert(observe(stackButton, output) == EFAULT);
    itemButton.fail = true;
    assert(observe(itemButton, output) == EFAULT);
    auto invalid = layout();
    invalid.count = invalid.stackItem;
    assert(!validElementLayout(invalid));
    invalid = layout();
    invalid.stackGetter = 256;
    assert(!validElementLayout(invalid));
    invalid = layout();
    invalid.durability = invalid.toolSize;
    assert(!validElementLayout(invalid));
    invalid = layout();
    invalid.itemType = invalid.toolType;
    assert(!validElementLayout(invalid));
    invalid = {};
    assert(validElementLayout(invalid));
    assert(collectElement(0, invalid, output) == 0 && !output.available);
    invalid.count = 1;
    assert(!validElementLayout(invalid));
}
