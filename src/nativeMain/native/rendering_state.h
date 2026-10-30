#pragma once
#include <stdint.h>

typedef struct FmRenderingState {
    uint64_t global, headless, settingsTable;
    uint32_t globalSize, settingsSize;
    uint32_t minimized, suppressed, settings, halt;
} FmRenderingState;

enum FmRenderingAdmission {
    FM_RENDERING_AVAILABLE = 0,
    FM_RENDERING_INVALID = 65536,
    FM_RENDERING_UNREADABLE,
    FM_RENDERING_SUSPENDED
};

#ifdef __cplusplus
// Read current values only at the frontend safe point; no borrowed pointers survive this call.
template <class Read> int renderingAdmission(const FmRenderingState &layout, Read read) {
    const auto range = [](uint64_t address, uint32_t size) {
        return address >= 4096 && size && address <= UINTPTR_MAX - size;
    };
    if (!range(layout.global, 8) || layout.global % 8 || !range(layout.headless, 1) ||
        layout.globalSize < 8 || layout.globalSize > 64 * 1024 * 1024 ||
        layout.settingsSize < 8 || layout.settingsSize > 64 * 1024 * 1024 ||
        layout.minimized >= layout.globalSize || layout.suppressed >= layout.globalSize ||
        layout.minimized == layout.suppressed || layout.settings % 8 ||
        layout.settings > layout.globalSize - 8 || layout.halt >= layout.settingsSize)
        return FM_RENDERING_INVALID;
    uint8_t headless, minimized, suppressed, halt;
    uint64_t global, settings, table;
    if (!read(layout.headless, headless) || headless > 1 || !read(layout.global, global) ||
        !range(global, layout.globalSize) || global % 8 ||
        !read(global + layout.minimized, minimized) || minimized > 1 ||
        !read(global + layout.suppressed, suppressed) || suppressed > 1)
        return FM_RENDERING_UNREADABLE;
    if (headless || suppressed)
        return FM_RENDERING_SUSPENDED;
    if (!minimized)
        return FM_RENDERING_AVAILABLE;
    if (!read(global + layout.settings, settings))
        return FM_RENDERING_UNREADABLE;
    if (!settings)
        return FM_RENDERING_AVAILABLE;
    if (!range(settings, layout.settingsSize) || settings % 8 ||
        (layout.settingsTable && (!read(settings, table) || table != layout.settingsTable)) ||
        !read(settings + layout.halt, halt) || halt > 1)
        return FM_RENDERING_UNREADABLE;
    return halt ? FM_RENDERING_SUSPENDED : FM_RENDERING_AVAILABLE;
}
#endif
