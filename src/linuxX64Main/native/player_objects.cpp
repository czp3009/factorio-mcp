#include "player_objects.h"
#include "linux_ipc.h"
#include "memory_read.h"
#include <cerrno>

namespace {
bool object(uintptr_t address, uint32_t size) {
    return fm::addressRange(address, size) && address % alignof(uintptr_t) == 0;
}

bool size(uint32_t value) {
    return value >= sizeof(uintptr_t) && value <= 64 * 1024 * 1024;
}

bool table(uintptr_t address, uintptr_t type) {
    return address >= 2 * sizeof(uintptr_t) && object(address, sizeof(uintptr_t)) && object(type, 2 * sizeof(uintptr_t));
}

int identity(uintptr_t pointer, uint32_t size, uintptr_t expected, uintptr_t type) {
    uintptr_t actual, adjustment, actualType;
    if (!object(pointer, size) || !fm::read(pointer, actual))
        return EFAULT;
    if (actual != expected)
        return ENOTSUP;
    if (!fm::read(actual - 2 * sizeof(uintptr_t), adjustment) || !fm::read(actual - sizeof(uintptr_t), actualType))
        return EFAULT;
    return adjustment || actualType != type ? ESTALE : 0;
}
} // namespace

bool validPlayerLayout(const FmLinuxPlayerLayout &layout) {
    return size(layout.gameSize) && size(layout.playerSize) && size(layout.viewSize) &&
           table(layout.playerVtable, layout.playerTypeInfo) && table(layout.viewVtable, layout.viewTypeInfo) &&
           layout.playerVtable != layout.viewVtable && layout.playerTypeInfo != layout.viewTypeInfo &&
           fm::member(layout.gamePlayer, 8, layout.gameSize) && layout.gamePlayer % 8 == 0 &&
           fm::member(layout.gameView, 8, layout.gameSize) && layout.gameView % 8 == 0 &&
           layout.gamePlayer != layout.gameView && layout.viewPlayer >= 8 && layout.viewPlayer % 8 == 0 &&
           fm::member(layout.viewPlayer, 8, layout.viewSize) && layout.index >= 8 &&
           (layout.indexWidth == 1 || layout.indexWidth == 2) &&
           fm::member(layout.index, layout.indexWidth, layout.playerSize);
}

int readLocalPlayer(uintptr_t game, const FmLinuxPlayerLayout &layout, const uint32_t *cancel, PlayerObjects &output) {
    output = {};
    if (!cancel || !validPlayerLayout(layout))
        return EINVAL;
    if (fm_ipc_load(cancel))
        return ECANCELED;
    PlayerObjects found;
    if (!object(game, layout.gameSize) || !fm::read(game + layout.gamePlayer, found.player))
        return EFAULT;
    if (!found.player) {
        uintptr_t view;
        if (!fm::read(game + layout.gameView, view))
            return EFAULT;
        if (!view)
            return ENOENT;
        if (const auto error = identity(view, layout.viewSize, layout.viewVtable, layout.viewTypeInfo))
            return error;
        if (!fm::read(view + layout.viewPlayer, found.player))
            return EFAULT;
        if (!found.player)
            return ENOENT;
    }
    if (const auto error = identity(found.player, layout.playerSize, layout.playerVtable, layout.playerTypeInfo))
        return error;
    if (layout.indexWidth == 1) {
        uint8_t index;
        if (!fm::read(found.player + layout.index, index))
            return EFAULT;
        found.index = index;
    } else {
        uint16_t index;
        if (!fm::read(found.player + layout.index, index))
            return EFAULT;
        found.index = index;
    }
    if (fm_ipc_load(cancel))
        return ECANCELED;
    output = found;
    return 0;
}
