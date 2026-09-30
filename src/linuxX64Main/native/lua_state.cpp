#include "lua_state.h"
#include "linux_ipc.h"
#include "memory_read.h"
#include <cerrno>
#include <climits>
#include <iterator>

bool validLuaStateLayout(const FmLinuxLuaStateLayout &layout) {
    if (layout.allocationSize < 16 || layout.allocationSize > 64 * 1024 * 1024 ||
        layout.globalOffset < 8 || layout.globalOffset > layout.allocationSize - 8 || layout.globalOffset % 8 ||
        !fm::member(layout.mainState, 8, layout.allocationSize - layout.globalOffset) ||
        layout.valueSize < 8 || layout.valueSize > 128 || (layout.valueSize & (layout.valueSize - 1)) ||
        layout.baseFrame >= layout.globalOffset ||
        !fm::member(layout.function, 8, layout.globalOffset - layout.baseFrame) ||
        !fm::member(layout.frameTop, 8, layout.globalOffset - layout.baseFrame))
        return false;
    const uint32_t pointers[] = {layout.global, layout.top, layout.stackBase, layout.stackEnd, layout.callInfo,
        layout.baseFrame + layout.function, layout.baseFrame + layout.frameTop, layout.handler};
    if (layout.status >= layout.globalOffset)
        return false;
    for (size_t index = 0; index < std::size(pointers); ++index) {
        const auto offset = pointers[index];
        if (!fm::member(offset, 8, layout.globalOffset) || offset % 8 ||
            (layout.status >= offset && layout.status < offset + 8))
            return false;
        for (size_t other = 0; other < index; ++other)
            if (!(offset + 8 <= pointers[other] || pointers[other] + 8 <= offset))
                return false;
    }
    return layout.mainState % 8 == 0;
}

int readLuaState(uintptr_t state, const FmLinuxLuaStateLayout &layout, const uint32_t *cancel, LuaStateObservation &output) {
    output = {};
    if (!cancel || !validLuaStateLayout(layout))
        return EINVAL;
    if (fm_ipc_load(cancel))
        return ECANCELED;
    if (!fm::addressRange(state, layout.allocationSize) || state % 8)
        return EFAULT;
    uintptr_t global, mainState, frame;
    if (!fm::read(state + layout.global, global))
        return EFAULT;
    if (global != state + layout.globalOffset)
        return ESTALE;
    if (!fm::read(global + layout.mainState, mainState))
        return EFAULT;
    if (mainState != state)
        return ESTALE;
    uint8_t status;
    if (!fm::read(state + layout.callInfo, frame) || !fm::read(state + layout.status, status))
        return EFAULT;
    if (frame != state + layout.baseFrame || status)
        return EBUSY;
    uintptr_t function, top, base, end, limit;
    LuaStateObservation found;
    if (!fm::read(state + layout.top, top) || !fm::read(state + layout.stackBase, base) ||
        !fm::read(state + layout.stackEnd, end) || !fm::read(frame + layout.function, function) ||
        !fm::read(frame + layout.frameTop, limit) || !fm::read(state + layout.handler, found.handler))
        return EFAULT;
    if (!fm::addressRange(base, layout.valueSize) || base % 8 || function != base ||
        top < base + layout.valueSize || limit < top || end < limit || end > INTPTR_MAX ||
        (top - base) % layout.valueSize || (limit - base) % layout.valueSize || (end - base) % layout.valueSize ||
        (end - base) / layout.valueSize > INT_MAX)
        return EFAULT;
    found.elements = static_cast<uint32_t>((top - base) / layout.valueSize - 1);
    found.capacity = static_cast<uint32_t>((end - top) / layout.valueSize);
    found.frameCapacity = static_cast<uint32_t>((limit - top) / layout.valueSize);
    if (fm_ipc_load(cancel))
        return ECANCELED;
    output = found;
    return 0;
}
