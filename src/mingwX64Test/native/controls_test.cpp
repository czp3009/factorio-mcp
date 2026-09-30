#include "controls.h"
#include <cassert>
#include <cstddef>
#include <memory>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Value {
    uint8_t type;
    uint32_t code;
    uint8_t modifiers;
};

struct Config {
    std::string key;
    Value value;
};

struct Prototype {
    bool enabled, spectating, cutscene;
};

struct Control {
    Config slots[4];
    Control *linked{};
    Prototype *custom{};
    bool gui{};
    int usage{};
};

struct Registry {
    Control **first, **last;
} registry;

bool loading{};
unsigned destroyed{};
uint32_t mouseCodes[] = {41, 75, 9, 16, 23};

void *list() {
    return &registry;
}

size_t length(const std::string *value) {
    return value->size();
}

const char *data(const std::string *value) {
    return value->data();
}

void *modifiers(void *, void *storage) {
    return new (storage) std::string("CONTROL + ");
}

void destroy(std::string *value) {
    value->~basic_string();
    ++destroyed;
}

template <class T> void bind(Symbols &symbols, FmSymbol symbol, T address) {
    symbols.address[symbol] = reinterpret_cast<uintptr_t>(address);
}
} // namespace

int main() {
    Symbols symbols{};
    auto &layout = symbols.controls;
    layout.first = offsetof(Registry, first);
    layout.last = offsetof(Registry, last);
    for (unsigned i = 0; i < 2; ++i)
        layout.slots[i] = offsetof(Control, slots) + i * sizeof(Config);
    layout.key = offsetof(Config, key);
    layout.value = offsetof(Config, value);
    layout.linked = offsetof(Control, linked);
    layout.custom = offsetof(Control, custom);
    layout.gui = offsetof(Control, gui);
    layout.usage = offsetof(Control, usage);
    layout.type = offsetof(Value, type);
    layout.code = offsetof(Value, code);
    layout.modifiers = offsetof(Value, modifiers);
    layout.enabled = offsetof(Prototype, enabled);
    layout.spectating = offsetof(Prototype, spectating);
    layout.cutscene = offsetof(Prototype, cutscene);
    layout.stringSize = sizeof(std::string);
    bind(symbols, ControlList, list);
    bind(symbols, ControlsLoading, &loading);
    bind(symbols, StringData, data);
    bind(symbols, StringSize, length);
    bind(symbols, StringDestroy, destroy);
    bind(symbols, BindingModifiers, modifiers);
    const FmSymbol buttons[] = {MouseLeft, MouseRight, MouseMiddle, Mouse4, Mouse5};
    for (unsigned i = 0; i < 5; ++i)
        bind(symbols, buttons[i], &mouseCodes[i]);
    Control native{}, mod{};
    native.slots[0] = {"build", {1, 99, 4}};
    native.slots[2] = {"controller-only", {4, 13, 1}};
    mod.slots[0].key = "linked-mod";
    mod.linked = &native;
    Prototype prototype{false, true, false};
    mod.custom = &prototype;
    Control *entries[] = {&native, &mod};
    registry = {entries, entries + 2};
    auto shared = std::make_unique<Shared>();
    collectControls(symbols, *shared);
    const auto &result = shared->result;
    assert(result.registryCount == 2 && result.controlCount == 2 && destroyed == 6);
    const auto &row = result.controls[1];
    assert(std::string(row.id) == "linked-mod" && std::string(row.bindingOwner) == "build");
    assert(row.custom && !row.enabled && row.spectating && !row.cutscene);
    assert(row.bindings[0].type == 0 && row.effective[0].code == 99 && row.effective[0].modifiers == 4);
    assert(row.mouseCodes[1] == 75);
    assert(std::string(row.effective[0].modifierExpression) == "CONTROL + ");
    auto rejected = [&] {
        try {
            collectControls(symbols, *shared);
            return false;
        } catch (const std::runtime_error &) {
            return true;
        }
    };
    native.linked = &mod;
    assert(rejected());
    native.linked = nullptr;
    native.slots[0].key.assign(256, 'x');
    assert(rejected());
    native.slots[0].key = "build";
    loading = true;
    assert(rejected());
    loading = false;
    shared->cancel = 1;
    assert(rejected());
    shared->cancel = 0;
    std::vector<Control> chain(FM_MAX_CONTROLS + 1);
    std::vector<Control *> linkedEntries;
    for (size_t i = 0; i < chain.size(); ++i) {
        chain[i].slots[0].key = "control-" + std::to_string(i);
        chain[i].linked = i + 1 < chain.size() ? &chain[i + 1] : nullptr;
        linkedEntries.push_back(&chain[i]);
    }
    chain.back().slots[0].value = {1, 123, 0};
    registry = {linkedEntries.data(), linkedEntries.data() + linkedEntries.size()};
    collectControls(symbols, *shared);
    assert(result.truncated && result.controlCount == FM_MAX_CONTROLS && result.registryCount == chain.size());
    for (unsigned i = 0; i < result.controlCount; ++i) {
        assert(result.controls[i].effective[0].code == 123);
        assert(std::string(result.controls[i].bindingOwner) == chain.back().slots[0].key);
    }
    chain.back().linked = &chain.front();
    assert(rejected());
    return 0;
}
