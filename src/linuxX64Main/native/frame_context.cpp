#include "frame_context.h"
#include "linux_ipc.h"
#include "memory_read.h"
#include <cerrno>
#include <initializer_list>
#include <sys/syscall.h>

namespace {
bool pointer(uintptr_t address) {
    return fm::addressRange(address, sizeof(uintptr_t)) && address % alignof(uintptr_t) == 0;
}

bool member(uint32_t offset, uint32_t extent) {
    return offset % alignof(uintptr_t) == 0 && fm::member(offset, sizeof(uintptr_t), extent);
}

int identity(uintptr_t object, uint32_t extent, uintptr_t table) {
    uintptr_t actual;
    if (!object)
        return ENOENT;
    if (!pointer(object) || !fm::addressRange(object, extent) || !fm::read(object, actual))
        return EFAULT;
    return actual == table ? 0 : ESTALE;
}
} // namespace

bool validFrameContext(const FmLinuxFrameContextConfig &config) {
    for (auto extent : {config.globalSize, config.graphicsSize, config.windowSize}) {
        if (extent < sizeof(uintptr_t) || extent > 64 * 1024 * 1024)
            return false;
    }
    return pointer(config.global) && pointer(config.device) && config.global != config.device &&
        fm::addressRange(config.caller, 1) && fm::addressRange(config.getter, 1) &&
        pointer(config.graphicsTable) && pointer(config.windowTable) &&
        config.graphicsTable != config.windowTable && member(config.globalWindow, config.globalSize) &&
        config.graphicsWindow >= sizeof(uintptr_t) && member(config.graphicsWindow, config.graphicsSize) &&
        config.windowGraphics >= sizeof(uintptr_t) && member(config.windowGraphics, config.windowSize) &&
        config.nativeWindow >= sizeof(uintptr_t) && member(config.nativeWindow, config.windowSize) &&
        config.windowGraphics != config.nativeWindow;
}

int readFrameSize(const FmLinuxFrameContextConfig &config, uintptr_t device, uintptr_t window,
                  uintptr_t caller, const uint32_t *cancel, FmLinuxFrameSize &output) noexcept {
    output = {};
    if (!cancel || !validFrameContext(config))
        return EINVAL;
    if (syscall(SYS_gettid) != getpid() || caller != config.caller)
        return EPERM;
    if (fm_ipc_load(cancel))
        return ECANCELED;
    if (!device || !window)
        return ENOENT;
    uintptr_t currentDevice, global, owner, graphics, back, native;
    if (!fm::read(config.device, currentDevice) || !fm::read(config.global, global))
        return EFAULT;
    if (currentDevice != device)
        return ESTALE;
    if (!global)
        return ENOENT;
    if (!pointer(global) || !fm::addressRange(global, config.globalSize) ||
        !fm::read(global + config.globalWindow, owner))
        return EFAULT;
    if (const int error = identity(owner, config.windowSize, config.windowTable))
        return error;
    if (!fm::read(owner + config.windowGraphics, graphics) || !fm::read(owner + config.nativeWindow, native))
        return EFAULT;
    if (native != window)
        return ESTALE;
    if (const int error = identity(graphics, config.graphicsSize, config.graphicsTable))
        return error;
    if (!fm::read(graphics + config.graphicsWindow, back))
        return EFAULT;
    if (back != owner)
        return ESTALE;
    if (fm_ipc_load(cancel))
        return ECANCELED;
    uint64_t dimensions;
    try {
        dimensions = reinterpret_cast<uint64_t (*)(void *)>(config.getter)(reinterpret_cast<void *>(graphics));
    } catch (...) {
        return EIO;
    }
    if (fm_ipc_load(cancel))
        return ECANCELED;
    const auto width = static_cast<uint32_t>(dimensions);
    const auto height = static_cast<uint32_t>(dimensions >> 32);
    if (!width || !height || width > 8192 || height > 8192 || uint64_t{width} * height > 16777216)
        return EOVERFLOW;
    output = {width, height};
    return 0;
}
