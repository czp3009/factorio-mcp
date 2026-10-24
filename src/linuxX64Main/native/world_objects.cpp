#include "world_objects.h"
#include "linux_ipc.h"
#include "memory_read.h"
#include <cerrno>
#include <cstring>
#include <initializer_list>

namespace {
bool object(uintptr_t address, uint32_t size) {
    return fm::addressRange(address, size) && address % alignof(uintptr_t) == 0;
}

bool size(uint32_t value) {
    return value >= sizeof(uintptr_t) && value <= 64 * 1024 * 1024;
}
} // namespace

bool validWorldLayout(const FmLinuxWorldLayout &layout) {
    if (!object(layout.global, sizeof(uintptr_t)) || !size(layout.globalSize) || !size(layout.scenarioSize) ||
        !size(layout.gameSize) || !size(layout.contextSize) ||
        !fm::member(layout.scenario, sizeof(uintptr_t), layout.globalSize) ||
        !fm::member(layout.game, sizeof(uintptr_t), layout.scenarioSize) ||
        !fm::member(layout.context, sizeof(uintptr_t), layout.scenarioSize) || layout.context % alignof(uintptr_t) ||
        !(layout.context + sizeof(uintptr_t) <= layout.game || layout.game + sizeof(uintptr_t) <= layout.context))
        return false;
    return true;
}

int readWorldObjects(const FmLinuxWorldLayout &layout, const uint32_t *cancel, WorldObjects &output) {
    output = {};
    if (!cancel || !validWorldLayout(layout))
        return EINVAL;
    if (fm_ipc_load(cancel))
        return ECANCELED;
    uintptr_t global;
    if (!fm::read(layout.global, global))
        return EFAULT;
    if (!global)
        return ENOENT;
    WorldObjects found;
    found.global = global;
    if (!object(global, layout.globalSize) || !fm::read(global + layout.scenario, found.scenario))
        return EFAULT;
    if (!found.scenario)
        return ENOENT;
    if (!object(found.scenario, layout.scenarioSize) || !fm::read(found.scenario + layout.game, found.game))
        return EFAULT;
    if (!found.game)
        return EAGAIN;
    if (!object(found.game, layout.gameSize))
        return EFAULT;
    if (fm_ipc_load(cancel))
        return ECANCELED;
    // LuaContext is nonpolymorphic. Its unique owner and extent are proven from typed destruction.
    if (!fm::read(found.scenario + layout.context, found.context))
        return EFAULT;
    if (!found.context)
        return EAGAIN;
    if (!object(found.context, layout.contextSize))
        return EFAULT;
    output = found;
    return 0;
}

bool validScriptLayout(const FmLinuxScriptLayout &layout) {
    if (!object(layout.vtable, sizeof(uintptr_t)) || layout.vtable < 2 * sizeof(uintptr_t) ||
        !object(layout.typeInfo, 2 * sizeof(uintptr_t)) || !size(layout.contextSize) || !size(layout.scriptSize) ||
        !fm::member(layout.begin, 8, layout.contextSize) || !fm::member(layout.end, 8, layout.contextSize) ||
        !(layout.begin + 8 <= layout.end || layout.end + 8 <= layout.begin) || layout.state < 8 ||
        !fm::member(layout.state, 8, layout.scriptSize) || layout.stringData > 4096 || layout.stringLength > 4096 ||
        !(layout.stringData + 8 <= layout.stringLength || layout.stringLength + 8 <= layout.stringData) ||
        !layout.expectedSize || layout.expectedSize > FM_LINUX_SCRIPT_NAME ||
        !fm::member(layout.name, layout.stringData + 8, layout.scriptSize) ||
        !fm::member(layout.name, layout.stringLength + 8, layout.scriptSize))
        return false;
    const auto data = layout.name + layout.stringData;
    const auto length = layout.name + layout.stringLength;
    if (layout.loading == layout.enabled)
        return false;
    for (auto flag : {layout.loading, layout.enabled}) {
        if (flag < 8 || flag >= layout.scriptSize || (flag >= data && flag < data + 8) ||
            (flag >= length && flag < length + 8) || (flag >= layout.state && flag < layout.state + 8))
            return false;
    }
    return data >= 8 && length >= 8 && (layout.state + 8 <= data || data + 8 <= layout.state) &&
           (layout.state + 8 <= length || length + 8 <= layout.state);
}

int readDefaultScript(uintptr_t context, const FmLinuxScriptLayout &layout, const uint32_t *cancel,
                      ScriptObjects &output) {
    output = {};
    if (!cancel || !validScriptLayout(layout))
        return EINVAL;
    if (fm_ipc_load(cancel))
        return ECANCELED;
    uintptr_t begin, end;
    if (!object(context, layout.contextSize) || !fm::read(context + layout.begin, begin) ||
        !fm::read(context + layout.end, end) || begin > end || end > INTPTR_MAX || begin % alignof(uintptr_t) ||
        end % alignof(uintptr_t) || (!begin && end))
        return EFAULT;
    if (begin == end)
        return ENOENT;
    ScriptObjects found;
    uintptr_t table, adjustment, typeInfo;
    if (!fm::read(begin, found.script) || !object(found.script, layout.scriptSize) || !fm::read(found.script, table))
        return EFAULT;
    if (table != layout.vtable)
        return ENOTSUP;
    if (!fm::read(table - 2 * sizeof(uintptr_t), adjustment) || !fm::read(table - sizeof(uintptr_t), typeInfo))
        return EFAULT;
    if (adjustment || typeInfo != layout.typeInfo)
        return ESTALE;
    uint64_t length;
    if (!fm::read(found.script + layout.name + layout.stringLength, length))
        return EFAULT;
    if (length != layout.expectedSize)
        return ENOENT;
    uintptr_t data;
    uint8_t name[FM_LINUX_SCRIPT_NAME];
    if (!fm::read(found.script + layout.name + layout.stringData, data) ||
        !fm::readBytes(data, name, layout.expectedSize))
        return EFAULT;
    if (std::memcmp(name, layout.expected, layout.expectedSize))
        return ENOENT;
    if (fm_ipc_load(cancel))
        return ECANCELED;
    uint8_t loading, enabled;
    if (!fm::read(found.script + layout.loading, loading) || !fm::read(found.script + layout.enabled, enabled) ||
        loading > 1 || enabled > 1)
        return EFAULT;
    if (loading || !enabled)
        return EAGAIN;
    if (!fm::read(found.script + layout.state, found.state))
        return EFAULT;
    if (!found.state)
        return EAGAIN;
    if (!object(found.state, sizeof(uintptr_t)))
        return EFAULT;
    output = found;
    return 0;
}
