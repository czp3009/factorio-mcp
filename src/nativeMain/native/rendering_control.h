#pragma once
#include <stdint.h>

typedef struct FmRenderingControl {
    uint64_t global, headless;
    uint32_t globalSize, suppressed, enabled;
} FmRenderingControl;

enum FmRenderingControlResult {
    FM_RENDERING_APPLIED = 0,
    FM_RENDERING_CONTROL_INVALID = 65540,
    FM_RENDERING_CONTROL_UNREADABLE,
    FM_RENDERING_CONTROL_UNWRITABLE,
    FM_RENDERING_CONTROL_HEADLESS
};

#ifdef __cplusplus
// One frontend operation, with no retained receiver, restoration or subsequent servicing.
template <class Read, class Write>
int setRendering(const FmRenderingControl &layout, Read read, Write write) {
    const auto range = [](uint64_t address, uint32_t size) {
        return address >= 4096 && size && address <= UINTPTR_MAX - size;
    };
    if (!range(layout.global, 8) || layout.global % 8 || !range(layout.headless, 1) ||
        layout.globalSize < 8 || layout.globalSize > 64 * 1024 * 1024 ||
        layout.suppressed >= layout.globalSize || layout.enabled > 1)
        return FM_RENDERING_CONTROL_INVALID;
    uint64_t global;
    uint8_t headless, suppressed;
    if (!read(layout.headless, headless) || headless > 1 || !read(layout.global, global) ||
        !range(global, layout.globalSize) || global % 8 ||
        !read(global + layout.suppressed, suppressed) || suppressed > 1)
        return FM_RENDERING_CONTROL_UNREADABLE;
    if (headless)
        return FM_RENDERING_CONTROL_HEADLESS;
    const uint8_t requested = !layout.enabled;
    if (suppressed == requested)
        return FM_RENDERING_APPLIED;
    return write(global + layout.suppressed, requested) ? FM_RENDERING_APPLIED
                                                       : FM_RENDERING_CONTROL_UNWRITABLE;
}
#endif
