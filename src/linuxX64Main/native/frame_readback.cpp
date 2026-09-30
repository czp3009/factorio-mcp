#include "frame_readback.h"
#include <algorithm>
#include <array>
#include <cerrno>

namespace {
// Khronos OpenGL enum values, not game layouts.
constexpr uint32_t readFramebuffer = 0x8ca8, readFramebufferBinding = 0x8caa;
constexpr uint32_t pixelPackBuffer = 0x88eb, pixelPackBufferBinding = 0x88ed;
constexpr uint32_t readBuffer = 0x0c02, back = 0x0405, rgb = 0x1907, unsignedByte = 0x1401;
constexpr std::array<uint32_t, 4> packFields{0x0d05, 0x0d02, 0x0d03, 0x0d04};
constexpr std::array<int32_t, 4> packed{1, 0, 0, 0};

struct PackState {
    const FrameReadbackApi &api;
    int32_t framebuffer = 0, buffer = 0;
    std::array<int32_t, 4> pack{};
    bool changed = false;

    explicit PackState(const FrameReadbackApi &value) : api(value) {
        api.getInteger(readFramebufferBinding, &framebuffer);
        api.getInteger(pixelPackBufferBinding, &buffer);
        for (size_t i = 0; i < pack.size(); ++i)
            api.getInteger(packFields[i], &pack[i]);
    }

    void apply() {
        changed = true;
        api.bindFramebuffer(readFramebuffer, 0);
        api.bindBuffer(pixelPackBuffer, 0);
        for (size_t i = 0; i < pack.size(); ++i)
            api.pixelStore(packFields[i], packed[i]);
    }

    ~PackState() {
        if (!changed)
            return;
        for (size_t i = 0; i < pack.size(); ++i)
            api.pixelStore(packFields[i], pack[i]);
        api.bindBuffer(pixelPackBuffer, static_cast<uint32_t>(buffer));
        api.bindFramebuffer(readFramebuffer, static_cast<uint32_t>(framebuffer));
    }
};
}

int readFrame(const FrameReadbackApi &api, uint32_t width, uint32_t height, unsigned char *pixels,
              size_t capacity, size_t &written, uint32_t &glError) {
    written = 0;
    glError = 0;
    if (!api.getInteger || !api.bindFramebuffer || !api.bindBuffer || !api.pixelStore ||
        !api.readPixels || !api.getError || !pixels || !width || !height || width > 8192 || height > 8192 ||
        uint64_t(width) * height > 16777216)
        return EINVAL;
    const size_t size = size_t(width) * height * 3;
    if (capacity < size)
        return ENOBUFS;
    if ((glError = api.getError()))
        return EPROTO;
    int result = 0;
    {
        PackState state(api);
        if ((glError = api.getError()))
            return EIO;
        state.apply();
        int32_t selected = 0;
        api.getInteger(readBuffer, &selected);
        glError = api.getError();
        if (glError)
            result = EIO;
        else if (selected != static_cast<int32_t>(back))
            result = ENOTSUP;
        else {
            api.readPixels(0, 0, static_cast<int32_t>(width), static_cast<int32_t>(height), rgb, unsignedByte, pixels);
            if ((glError = api.getError()))
                result = EIO;
        }
    }
    const uint32_t restoreError = api.getError();
    if (!glError)
        glError = restoreError;
    if (restoreError)
        result = EIO;
    if (result)
        return result;
    const size_t stride = size_t(width) * 3;
    for (uint32_t y = 0; y < height / 2; ++y)
        std::swap_ranges(pixels + size_t(y) * stride, pixels + size_t(y + 1) * stride,
                         pixels + size_t(height - y - 1) * stride);
    written = size;
    return 0;
}
