#include "controls.h"
#include "linux_ipc.h"
#include "memory_read.h"
#include <algorithm>
#include <array>
#include <cerrno>
#include <cstring>
#include <new>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace {
using Object = std::array<unsigned char, 4096>;

template<class T> T field(const Object& object, uint32_t offset) {
    T result;
    std::memcpy(&result, object.data() + offset, sizeof(result));
    return result;
}

int identity(const Object& object, const FmLinuxControlsLayout& layout, char* output) {
    const auto data = field<uintptr_t>(object, layout.nameData);
    const auto length = field<uint64_t>(object, layout.nameLength);
    if (!length || length >= FM_LINUX_CONTROL_ID_BYTES) return EOVERFLOW;
    if (!fm::readBytes(data, output, length)) return EFAULT;
    if (std::memchr(output, 0, length)) return EINVAL;
    output[length] = 0;
    return 0;
}

void bindings(const Object& object, const FmLinuxControlsLayout& layout, FmLinuxBinding* output) {
    for (unsigned slot = 0; slot < 2; ++slot) {
        output[slot].type = field<uint8_t>(object, layout.slots[slot] + layout.type);
        output[slot].code = field<uint32_t>(object, layout.slots[slot] + layout.code);
        output[slot].modifiers = field<uint8_t>(object, layout.slots[slot] + layout.modifiers);
    }
}
}

bool validControlsLayout(const FmLinuxControlsLayout& layout) {
    if (!fm::addressRange(layout.registry, layout.registrySize) || !fm::addressRange(layout.guard, 1) ||
        !fm::addressRange(layout.loading, 1) || !layout.prototypeVtable ||
        layout.registrySize > 4096 || layout.size < 8 || layout.size > 4096 ||
        layout.prototypeSize < 8 || layout.prototypeSize > 4096 ||
        !fm::member(layout.begin, 8, layout.registrySize) || !fm::member(layout.end, 8, layout.registrySize) ||
        (layout.begin < layout.end + 8 && layout.end < layout.begin + 8) || !layout.guiMask || layout.guiMask > 255 ||
        (layout.guiMask & (layout.guiMask - 1)) || layout.slots[0] == layout.slots[1]) return false;
    for (const auto offset : {layout.nameData, layout.nameLength, layout.linked, layout.custom})
        if (!fm::member(offset, 8, layout.size)) return false;
    if (!fm::member(layout.gui, 1, layout.size) || !fm::member(layout.usage, 4, layout.size)) return false;
    for (const auto slot : layout.slots) {
        if (slot >= layout.size || !fm::member(layout.type, 1, layout.size - slot) ||
            !fm::member(layout.code, 4, layout.size - slot) || !fm::member(layout.modifiers, 1, layout.size - slot)) return false;
    }
    for (const auto offset : {layout.enabled, layout.spectating, layout.cutscene})
        if (offset < 8 || !fm::member(offset, 1, layout.prototypeSize)) return false;
    if (layout.enabled == layout.spectating || layout.enabled == layout.cutscene || layout.spectating == layout.cutscene)
        return false;
    const std::pair<uint32_t, uint32_t> fields[] = {
        {layout.nameData, 8}, {layout.nameLength, 8}, {layout.linked, 8}, {layout.custom, 8},
        {layout.gui, 1}, {layout.usage, 4},
        {layout.slots[0] + layout.type, 1}, {layout.slots[0] + layout.code, 4}, {layout.slots[0] + layout.modifiers, 1},
        {layout.slots[1] + layout.type, 1}, {layout.slots[1] + layout.code, 4}, {layout.slots[1] + layout.modifiers, 1},
    };
    for (size_t left = 0; left < std::size(fields); ++left)
        for (size_t right = left + 1; right < std::size(fields); ++right)
            if (fields[left].first < fields[right].first + fields[right].second &&
                fields[right].first < fields[left].first + fields[left].second) return false;
    return true;
}

int snapshotControls(const FmLinuxControlsLayout& layout, const uint32_t* cancel, FmLinuxControlsSnapshot& output) {
    output.count = output.registryCount = output.truncated = 0;
    if (!validControlsLayout(layout) || !cancel) return EINVAL;
    if (fm_ipc_load(cancel)) return ECANCELED;
    uint8_t ready, loading;
    if (!fm::read(layout.guard, ready) || !fm::read(layout.loading, loading)) return EFAULT;
    if (ready != 1 || loading) return EAGAIN;
    uintptr_t begin, end;
    if (!fm::read(layout.registry + layout.begin, begin) || !fm::read(layout.registry + layout.end, end)) return EFAULT;
    if (!begin || end < begin || (end - begin) % sizeof(uintptr_t)) return EINVAL;
    const auto count = (end - begin) / sizeof(uintptr_t);
    if (!count || count > 16384) return EOVERFLOW;
    try {
        std::vector<uintptr_t> entries(count);
        if (!fm::readBytes(begin, entries.data(), entries.size() * sizeof(uintptr_t))) return EFAULT;
        std::unordered_set<uintptr_t> registered(entries.begin(), entries.end());
        if (registered.size() != count || registered.contains(0)) return EINVAL;
        for (const auto entry : entries)
            if (!fm::addressRange(entry, layout.size)) return EFAULT;
        std::unordered_map<uintptr_t, uintptr_t> owners;
        std::vector<uintptr_t> path;
        Object object, linked, prototype;
        output.registryCount = static_cast<uint32_t>(count);
        output.truncated = count > FM_LINUX_MAX_CONTROLS;
        for (size_t index = 0; index < std::min(count, size_t(FM_LINUX_MAX_CONTROLS)); ++index) {
            if (fm_ipc_load(cancel)) return ECANCELED;
            const auto control = entries[index];
            if (!fm::readBytes(control, object.data(), layout.size)) return EFAULT;
            auto& row = output.controls[index];
            row = {};
            int error = identity(object, layout, row.id);
            if (error) return error;
            row.gui = (object[layout.gui] & layout.guiMask) != 0;
            row.usage = field<int32_t>(object, layout.usage);
            const auto custom = field<uintptr_t>(object, layout.custom);
            row.custom = custom != 0;
            if (custom) {
                uintptr_t table;
                if (!fm::read(custom, table)) return EFAULT;
                if (table != layout.prototypeVtable) return ESTALE;
                if (!fm::readBytes(custom, prototype.data(), layout.prototypeSize)) return EFAULT;
                row.enabled = prototype[layout.enabled];
                row.spectating = prototype[layout.spectating];
                row.cutscene = prototype[layout.cutscene];
                if (row.enabled > 1 || row.spectating > 1 || row.cutscene > 1) return EINVAL;
            }
            if (const auto next = field<uintptr_t>(object, layout.linked)) {
                if (!registered.contains(next)) return EINVAL;
                if (!fm::readBytes(next, linked.data(), layout.size)) return EFAULT;
                if ((error = identity(linked, layout, row.linked))) return error;
            }
            path.clear();
            auto owner = control;
            for (;;) {
                if (fm_ipc_load(cancel)) return ECANCELED;
                if (const auto known = owners.find(owner); known != owners.end()) {
                    owner = known->second;
                    break;
                }
                path.push_back(owner);
                uintptr_t next;
                if (!fm::read(owner + layout.linked, next)) return EFAULT;
                if (!next) break;
                if (path.size() >= count || !registered.contains(next)) return EINVAL;
                owner = next;
            }
            for (const auto entry : path) owners.emplace(entry, owner);
            bindings(object, layout, row.bindings);
            if (owner == control) {
                std::memcpy(row.owner, row.id, sizeof(row.owner));
                std::memcpy(row.effective, row.bindings, sizeof(row.bindings));
            } else {
                if (!fm::readBytes(owner, linked.data(), layout.size)) return EFAULT;
                if ((error = identity(linked, layout, row.owner))) return error;
                bindings(linked, layout, row.effective);
            }
            ++output.count;
        }
        return 0;
    } catch (const std::bad_alloc&) {
        return ENOMEM;
    }
}
