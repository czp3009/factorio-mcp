#pragma once
#include <stdint.h>

typedef struct FmRenderingControl {
    uint64_t global, headless;
    uint32_t globalSize, suppressed;
} FmRenderingControl;

enum FmRenderingControlResult {
    FM_RENDERING_STOPPED = 0,
    FM_RENDERING_CONTROL_INVALID = 65540,
    FM_RENDERING_CONTROL_UNREADABLE,
    FM_RENDERING_CONTROL_UNWRITABLE,
    FM_RENDERING_CONTROL_HEADLESS
};

#ifdef __cplusplus
// One frontend operation, with no retained receiver, restoration or subsequent servicing.
template <class Read, class Write>
int stopRendering(const FmRenderingControl &layout, Read read, Write write) {
    const auto range = [](uint64_t address, uint32_t size) {
        return address >= 4096 && size && address <= UINTPTR_MAX - size;
    };
    if (!range(layout.global, 8) || layout.global % 8 || !range(layout.headless, 1) ||
        layout.globalSize < 8 || layout.globalSize > 64 * 1024 * 1024 ||
        layout.suppressed >= layout.globalSize)
        return FM_RENDERING_CONTROL_INVALID;
    uint64_t global;
    uint8_t headless, suppressed;
    if (!read(layout.headless, headless) || headless > 1 || !read(layout.global, global) ||
        !range(global, layout.globalSize) || global % 8 ||
        !read(global + layout.suppressed, suppressed) || suppressed > 1)
        return FM_RENDERING_CONTROL_UNREADABLE;
    if (headless)
        return FM_RENDERING_CONTROL_HEADLESS;
    if (suppressed)
        return FM_RENDERING_STOPPED;
    return write(global + layout.suppressed, uint8_t{1}) ? FM_RENDERING_STOPPED
                                                       : FM_RENDERING_CONTROL_UNWRITABLE;
}
#endif
