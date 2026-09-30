#pragma once
#include <cstddef>
#include <cstdint>

// OpenGL 3.3 entry signatures. Resolve them for the verified current game render context before use.
struct FrameReadbackApi {
    void (*getInteger)(uint32_t, int32_t *);
    void (*bindFramebuffer)(uint32_t, uint32_t);
    void (*bindBuffer)(uint32_t, uint32_t);
    void (*pixelStore)(uint32_t, int32_t);
    void (*readPixels)(int32_t, int32_t, int32_t, int32_t, uint32_t, uint32_t, void *);
    uint32_t (*getError)();
};

// Called only before presentation on the established rendering thread/context. Dimensions must describe the
// verified window framebuffer, not an inferred viewport. Reads the default double-buffered back buffer.
// Produces top-down RGB bytes; written remains zero on failure. GL diagnostics are consumed and returned to
// the caller, including a pre-existing error (which rejects capture before state changes).
int readFrame(const FrameReadbackApi &api, uint32_t width, uint32_t height, unsigned char *pixels,
              size_t capacity, size_t &written, uint32_t &glError);
