#include "controls.h"
#include "protocol.h"
#include <algorithm>
#include <cstring>
#include <unordered_map>
#include <unordered_set>

namespace {
template <class T> T read(const void *object, unsigned offset) {
    T value;
    memcpy(&value, static_cast<const unsigned char *>(object) + offset, sizeof(value));
    return value;
}

template <class T> T function(const Symbols &symbols, FmSymbol id) {
    return reinterpret_cast<T>(symbols.address[id]);
}

bool copyString(const Symbols &symbols, const void *source, char *output, size_t capacity, bool identity = false) {
    const auto length = function<size_t (*)(const void *)>(symbols, StringSize)(source);
    require(length <= 65536, "Control metadata string exceeds bound");
    require(!identity || length < capacity, "Control identity exceeds supported length");
    const char *data = function<const char *(*)(const void *)>(symbols, StringData)(source);
    require(!identity || (length && !memchr(data, 0, length)), "Control identity is empty or contains NUL");
    size_t count = std::min(length, capacity - 1);
    if (count < length)
        while (count && (static_cast<unsigned char>(data[count]) & 0xc0) == 0x80)
            --count;
    memcpy(output, data, count);
    output[count] = 0;
    return count < length;
}

bool modifierExpression(const Symbols &symbols, void *value, char *output, size_t capacity) {
    // Verified Win64 member return ABI: receiver in RCX, uninitialized string storage in RDX.
    alignas(16) unsigned char storage[256];
    require(symbols.controls.stringSize <= sizeof(storage), "Unsupported game string size");
    function<void *(*)(void *, void *)>(symbols, BindingModifiers)(value, storage);

    struct Destroy {
        const Symbols &symbols;
        void *storage;

        ~Destroy() {
            function<void (*)(void *)>(symbols, StringDestroy)(storage);
        }
    } cleanup{symbols, storage};

    return copyString(symbols, storage, output, capacity);
}
} // namespace

void collectControls(const Symbols &symbols, Shared &shared) {
    require(!*reinterpret_cast<const bool *>(symbols.address[ControlsLoading]), "Control registry is being rebuilt");
    const auto &layout = symbols.controls;
    void *list = function<void *(*)()>(symbols, ControlList)();
    const auto first = read<uintptr_t>(list, layout.first), last = read<uintptr_t>(list, layout.last);
    require(last >= first && (last - first) % sizeof(void *) == 0 && (last - first) / sizeof(void *) <= 16384,
            "Control registry exceeds supported bound");
    const size_t count = (last - first) / sizeof(void *);
    require(count && first, "Control registry is not ready");
    auto **entries = reinterpret_cast<void **>(first);
    std::unordered_set<void *> registered(entries, entries + count);
    require(registered.size() == count && !registered.contains(nullptr), "Invalid control registry membership");
    // Shared linked tails are resolved once per snapshot, without a cross-frame object cache.
    std::unordered_map<void *, void *> owners;
    auto bindingOwner = [&](void *control) {
        std::vector<void *> path;
        void *owner = control;
        for (;;) {
            require(!shared.cancel, "Control query cancelled");
            const auto known = owners.find(owner);
            if (known != owners.end()) {
                owner = known->second;
                break;
            }
            path.push_back(owner);
            void *linked = read<void *>(owner, layout.linked);
            if (!linked)
                break;
            require(path.size() < count && registered.contains(linked), "Invalid linked control chain");
            owner = linked;
        }
        for (void *entry : path)
            owners.emplace(entry, owner);
        return owner;
    };
    auto key = [&](void *control, char *output, size_t capacity) {
        copyString(symbols, static_cast<unsigned char *>(control) + layout.slots[0] + layout.key, output, capacity,
                   true);
    };
    auto bindings = [&](void *control, FmBinding *output) {
        for (unsigned slot = 0; slot < 2; ++slot) {
            auto *value = static_cast<unsigned char *>(control) + layout.slots[slot] + layout.value;
            output[slot].type = read<uint8_t>(value, layout.type);
            output[slot].code = read<uint32_t>(value, layout.code);
            output[slot].modifiers = read<uint8_t>(value, layout.modifiers);
            require(!modifierExpression(symbols, value, output[slot].modifierExpression,
                                        sizeof(output[slot].modifierExpression)),
                    "Binding modifier expression exceeds bound");
        }
    };
    auto &result = shared.result;
    result.registryCount = static_cast<uint32_t>(count);
    result.controlCount = static_cast<uint32_t>(std::min(count, size_t(FM_MAX_CONTROLS)));
    result.truncated = count > FM_MAX_CONTROLS;
    for (size_t i = 0; i < result.controlCount; ++i) {
        require(!shared.cancel, "Control query cancelled");
        void *control = entries[i];
        auto &row = result.controls[i];
        memset(&row, 0, sizeof(row));
        key(control, row.id, sizeof(row.id));
        row.gui = read<bool>(control, layout.gui);
        row.usage = read<int>(control, layout.usage);
        void *prototype = read<void *>(control, layout.custom);
        row.custom = prototype != nullptr;
        if (prototype) {
            row.enabled = read<bool>(prototype, layout.enabled);
            row.spectating = read<bool>(prototype, layout.spectating);
            row.cutscene = read<bool>(prototype, layout.cutscene);
        }
        void *owner = bindingOwner(control);
        if (void *linked = read<void *>(control, layout.linked))
            key(linked, row.linked, sizeof(row.linked));
        key(owner, row.bindingOwner, sizeof(row.bindingOwner));
        bindings(control, row.bindings);
        if (owner == control)
            memcpy(row.effective, row.bindings, sizeof(row.bindings));
        else
            bindings(owner, row.effective);
        const FmSymbol buttons[] = {MouseLeft, MouseRight, MouseMiddle, Mouse4, Mouse5};
        for (unsigned button = 0; button < 5; ++button)
            row.mouseCodes[button] =
                read<uint32_t>(reinterpret_cast<void *>(symbols.address[buttons[button]]), layout.mouseValue);
    }
}
