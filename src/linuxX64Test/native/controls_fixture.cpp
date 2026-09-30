#include "controls.h"
#include <cassert>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>

template<unsigned Padding> struct Prototype {
    uintptr_t vtable;
    char padding[Padding];
    uint8_t enabled, spectating, cutscene;
};

template<unsigned Padding> struct Binding {
    char padding[Padding];
    uint8_t type;
    uint32_t code;
    uint8_t modifiers;
};

template<unsigned Padding> struct Control {
    char padding[Padding];
    uint8_t gui;
    const char* name;
    size_t nameLength;
    Control* linked;
    Prototype<Padding>* custom;
    int32_t usage;
    Binding<Padding> primary;
    char middle[Padding];
    Binding<Padding> secondary;
};

struct Registry {
    uintptr_t begin;
    uintptr_t end;
};

template<unsigned Padding> void run() {
    using C = Control<Padding>;
    using P = Prototype<Padding>;
    using B = Binding<Padding>;
    uint8_t guard = 1, loading = 0;
    uint32_t cancel = 0;
    uintptr_t vtable[2]{};
    P prototype{};
    prototype.vtable = reinterpret_cast<uintptr_t>(vtable);
    prototype.enabled = 1;
    prototype.cutscene = 1;
    C native{}, custom{}, linked{};
    native.name = "native";
    native.nameLength = 6;
    native.usage = 31;
    native.primary.type = 13;
    native.primary.code = 93;
    native.primary.modifiers = 0x83;
    native.secondary.type = 29;
    native.secondary.code = 7;
    custom.name = "mod.control";
    custom.nameLength = 11;
    custom.custom = &prototype;
    custom.linked = &native;
    custom.primary.type = 9;
    custom.primary.code = 22;
    linked.name = "linked";
    linked.nameLength = 6;
    linked.linked = &custom;
    linked.gui = 4;
    C* entries[] = {&native, &custom, &linked};
    Registry registry{reinterpret_cast<uintptr_t>(entries), reinterpret_cast<uintptr_t>(entries + 3)};
    FmLinuxControlsLayout layout{};
    layout.registry = reinterpret_cast<uintptr_t>(&registry);
    layout.guard = reinterpret_cast<uintptr_t>(&guard);
    layout.loading = reinterpret_cast<uintptr_t>(&loading);
    layout.prototypeVtable = prototype.vtable;
    layout.registrySize = sizeof(Registry);
    layout.begin = offsetof(Registry, begin);
    layout.end = offsetof(Registry, end);
    layout.size = sizeof(C);
    layout.nameData = offsetof(C, name);
    layout.nameLength = offsetof(C, nameLength);
    layout.linked = offsetof(C, linked);
    layout.custom = offsetof(C, custom);
    layout.gui = offsetof(C, gui);
    layout.guiMask = 4;
    layout.usage = offsetof(C, usage);
    layout.slots[0] = offsetof(C, primary);
    layout.slots[1] = offsetof(C, secondary);
    layout.type = offsetof(B, type);
    layout.code = offsetof(B, code);
    layout.modifiers = offsetof(B, modifiers);
    layout.prototypeSize = sizeof(P);
    layout.enabled = offsetof(P, enabled);
    layout.spectating = offsetof(P, spectating);
    layout.cutscene = offsetof(P, cutscene);
    auto result = std::make_unique<FmLinuxControlsSnapshot>();
    auto observe = [&]() { return snapshotControls(layout, &cancel, *result); };
    assert(validControlsLayout(layout) && observe() == 0);
    assert(result->count == 3 && result->registryCount == 3 && !result->truncated);
    const auto& first = result->controls[0];
    const auto& second = result->controls[1];
    const auto& third = result->controls[2];
    assert(!first.custom && !first.gui && first.usage == 31 && std::strcmp(first.owner, "native") == 0);
    assert(first.bindings[0].type == 13 && first.bindings[0].code == 93 && first.bindings[0].modifiers == 0x83);
    assert(first.bindings[1].type == 29 && first.bindings[1].code == 7);
    assert(second.custom && second.enabled && !second.spectating && second.cutscene);
    assert(second.bindings[0].code == 22 && second.effective[0].code == 93 && second.effective[1].code == 7);
    assert(std::strcmp(second.linked, "native") == 0 && std::strcmp(third.linked, "mod.control") == 0);
    assert(std::strcmp(third.owner, "native") == 0 && third.gui && !third.custom);
    native.primary.code = 41;
    assert(observe() == 0 && result->controls[2].effective[0].code == 41);
    guard = 0;
    assert(observe() == EAGAIN && result->count == 0);
    guard = 1;
    loading = 1;
    assert(observe() == EAGAIN);
    loading = 0;
    cancel = 1;
    assert(observe() == ECANCELED && result->count == 0);
    cancel = 0;
    native.linked = &linked;
    assert(observe() == EINVAL);
    native.linked = nullptr;
    custom.linked = reinterpret_cast<C*>(1);
    assert(observe() == EINVAL);
    custom.linked = &native;
    prototype.vtable++;
    assert(observe() == ESTALE);
    prototype.vtable--;
    const auto page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    auto* memory = static_cast<unsigned char*>(mmap(nullptr, page * 2, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    assert(memory != MAP_FAILED && mprotect(memory + page, page, PROT_NONE) == 0);
    const uintptr_t wrongTable = prototype.vtable + sizeof(uintptr_t);
    std::memcpy(memory + page - sizeof(uintptr_t), &wrongTable, sizeof(wrongTable));
    custom.custom = reinterpret_cast<P*>(memory + page - sizeof(uintptr_t));
    assert(observe() == ESTALE);
    custom.custom = &prototype;
    assert(munmap(memory, page * 2) == 0);
    prototype.enabled = 2;
    assert(observe() == EINVAL);
    prototype.enabled = 1;
    native.nameLength = FM_LINUX_CONTROL_ID_BYTES;
    assert(observe() == EOVERFLOW);
    native.nameLength = 6;
    native.name = "bad\0id";
    assert(observe() == EINVAL);
    native.name = reinterpret_cast<const char*>(1);
    assert(observe() == EFAULT);
    native.name = "native";
    entries[2] = &native;
    assert(observe() == EINVAL);
    entries[2] = &linked;
    const auto good = layout;
    layout.code = layout.type;
    assert(observe() == EINVAL);
    layout = good;
    layout.slots[1] = layout.size;
    assert(observe() == EINVAL);
    layout = good;
    layout.cutscene = layout.enabled;
    assert(observe() == EINVAL);
    layout = good;
    std::vector<C> many(FM_LINUX_MAX_CONTROLS + 1);
    std::vector<C*> pointers;
    for (auto& value : many) {
        value.name = "row";
        value.nameLength = 3;
        pointers.push_back(&value);
    }
    registry = {reinterpret_cast<uintptr_t>(pointers.data()), reinterpret_cast<uintptr_t>(pointers.data() + pointers.size())};
    assert(observe() == 0 && result->count == FM_LINUX_MAX_CONTROLS &&
        result->registryCount == FM_LINUX_MAX_CONTROLS + 1 && result->truncated);
}

int main() {
    run<1>();
    run<23>();
}
