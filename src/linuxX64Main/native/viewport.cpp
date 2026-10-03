#include "viewport.h"
#include "linux_ipc.h"
#include "memory_read.h"
#include <cerrno>
#include <cmath>
#include <cstring>
#include <limits>

namespace {
bool object(uintptr_t pointer, uint32_t size) {
    return size >= 8 && size <= 64 * 1024 * 1024 && pointer % alignof(uintptr_t) == 0 &&
           fm::addressRange(pointer, size);
}

int identity(uintptr_t pointer, uint32_t size, uintptr_t table, uintptr_t type) {
    uintptr_t actual, adjustment, actualType;
    if (!object(pointer, size) || !fm::read(pointer, actual))
        return EFAULT;
    if (actual != table)
        return ENOTSUP;
    if (table < 16 || !fm::read(table - 16, adjustment) || !fm::read(table - 8, actualType))
        return EFAULT;
    return adjustment || actualType != type ? ESTALE : 0;
}

bool layoutValid(const FmLinuxPlayerLayout &selection, const FmLinuxViewportLayout &layout) {
    return validPlayerLayout(selection) && layout.width && layout.height && layout.mapPosition &&
           layout.width != layout.height && layout.framebufferVtable >= 16 && layout.framebufferTypeInfo &&
           layout.rendererSize >= 8 && layout.rendererSize <= 64 * 1024 * 1024 &&
           layout.framebufferSize >= 8 && layout.framebufferSize <= 64 * 1024 * 1024 &&
           fm::member(layout.renderer, 8, selection.viewSize) && layout.renderer % 8 == 0 &&
           fm::member(layout.framebufferReference, 8, layout.rendererSize) && layout.framebufferReference % 8 == 0 &&
           fm::member(layout.primary, 8, layout.framebufferSize) && fm::member(layout.fallback, 8, layout.framebufferSize) &&
           (layout.primary + 8 <= layout.fallback || layout.fallback + 8 <= layout.primary) &&
           layout.widthSlot <= 8191 && layout.heightSlot <= 8191 && layout.widthSlot != layout.heightSlot &&
           fm::member(layout.surface, 4, selection.viewSize) && fm::member(layout.position, 8, selection.viewSize) &&
           (layout.surface + 4 <= layout.position || layout.position + 8 <= layout.surface) && layout.fractionBits <= 30;
}
} // namespace

int readViewport(uintptr_t game, uintptr_t player, const FmLinuxPlayerLayout &selection,
                 const FmLinuxViewportLayout &layout, const uint32_t *cancel, QueryViewport &output) {
    output = {};
    if (!cancel || !layoutValid(selection, layout))
        return EINVAL;
    if (fm_ipc_load(cancel))
        return ECANCELED;
    uintptr_t view, viewPlayer;
    if (!object(game, selection.gameSize) || !fm::read(game + selection.gameView, view))
        return EFAULT;
    if (!view)
        return ENOENT;
    if (const auto error = identity(view, selection.viewSize, selection.viewVtable, selection.viewTypeInfo))
        return error;
    if (!fm::read(view + selection.viewPlayer, viewPlayer))
        return EFAULT;
    if (!player || viewPlayer != player)
        return ENOENT;
    uint32_t surface;
    int32_t cached[2];
    if (!fm::read(view + layout.surface, surface) || !fm::readBytes(view + layout.position, cached, sizeof(cached)))
        return EFAULT;
    if (surface == UINT32_MAX)
        return ENOENT;
    for (const auto coordinate : cached)
        if (coordinate == std::numeric_limits<int32_t>::max() ||
            std::abs(static_cast<double>(coordinate)) > std::ldexp(1000000.0, layout.fractionBits))
            return ENOENT;
    uintptr_t renderer, reference, framebuffer;
    if (!fm::read(view + layout.renderer, renderer))
        return EFAULT;
    if (!renderer)
        return ENOENT;
    if (!object(renderer, layout.rendererSize) || !fm::read(renderer + layout.framebufferReference, reference))
        return EFAULT;
    if (!reference)
        return ENOENT;
    if (!object(reference, sizeof(uintptr_t)) || !fm::read(reference, framebuffer))
        return EFAULT;
    if (!framebuffer)
        return ENOENT;
    if (const auto error = identity(framebuffer, layout.framebufferSize, layout.framebufferVtable, layout.framebufferTypeInfo))
        return error;
    uintptr_t width, height, primary, fallback;
    if (!fm::read(layout.framebufferVtable + layout.widthSlot * 8, width) ||
        !fm::read(layout.framebufferVtable + layout.heightSlot * 8, height) ||
        !fm::read(framebuffer + layout.primary, primary) || !fm::read(framebuffer + layout.fallback, fallback))
        return EFAULT;
    if (width != layout.width || height != layout.height)
        return ESTALE;
    // These verified getter paths abort if both backing objects are absent. No call is made in that state.
    if (!primary && !fallback)
        return ENOENT;
    const auto backing = primary ? primary : fallback;
    if (!object(backing, sizeof(uintptr_t)))
        return EFAULT;
    auto *nativeFramebuffer = reinterpret_cast<void *>(framebuffer);
    const auto nativeWidth = reinterpret_cast<int32_t (*)(void *)>(width)(nativeFramebuffer);
    const auto nativeHeight = reinterpret_cast<int32_t (*)(void *)>(height)(nativeFramebuffer);
    if (nativeWidth <= 1 || nativeHeight <= 1 || nativeWidth > 32768 || nativeHeight > 32768)
        return ERANGE;
    QueryViewport result;
    result.surface = static_cast<double>(surface) + 1;
    result.width = nativeWidth;
    result.height = nativeHeight;
    auto position = [&](int32_t x, int32_t y, double &mapX, double &mapY) {
        const uint64_t pixel = static_cast<uint32_t>(x) | (static_cast<uint64_t>(static_cast<uint32_t>(y)) << 32);
        const auto getter = reinterpret_cast<uint64_t (*)(void *, uint64_t)>(layout.mapPosition);
        const auto packed = getter(reinterpret_cast<void *>(view), pixel);
        const uint32_t low = packed, high = packed >> 32;
        int32_t components[2];
        std::memcpy(&components[0], &low, sizeof(low));
        std::memcpy(&components[1], &high, sizeof(high));
        mapX = std::ldexp(static_cast<double>(components[0]), -static_cast<int>(layout.fractionBits));
        mapY = std::ldexp(static_cast<double>(components[1]), -static_cast<int>(layout.fractionBits));
    };
    position(0, 0, result.left, result.top);
    position(nativeWidth, nativeHeight, result.right, result.bottom);
    if (result.right <= result.left || result.bottom <= result.top)
        return ERANGE;
    if (fm_ipc_load(cancel))
        return ECANCELED;
    output = result;
    return 0;
}
