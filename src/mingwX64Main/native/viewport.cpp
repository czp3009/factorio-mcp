#include "viewport.h"
#include "protocol.h"
#include "../../nativeMain/native/input_world_projection.h"
#include <cmath>
#include <cstring>

namespace {
template <class T> T read(const void *object, unsigned offset) {
    T value;
    memcpy(&value, static_cast<const unsigned char *>(object) + offset, sizeof(value));
    return value;
}
} // namespace

static ViewportSnapshot viewport(const Symbols &symbols, void *player, const InputPoint *point, InputPosition *output) {
    const auto &layout = symbols.viewport;
    require(layout.supported && player, "Viewport adapter is unavailable");
    void *view = reinterpret_cast<void *(*)(void *)>(symbols.address[PlayerGameView])(player);
    require(view, "The local player has no GameView");
    // PDB validation fixes the coordinate representation to FixedPointNumberTemplate<int,8,0>.
    // Reject the optional empty-position representation and coordinates outside the world's bounds.
    for (auto offset : {layout.cachedX, layout.cachedY})
        require(std::abs(double(read<int32_t>(view, offset))) <= 1000000.0 * 256,
                "The current view has no valid cached map position");
    ViewportSnapshot result;
    const auto nativeSurface = read<uint32_t>(view, layout.surfaceIndex);
    require(nativeSurface < UINT32_MAX, "The current view has no surface");
    // LuaHelper::push(SurfaceIndex) exposes the native zero-based index plus one.
    result.surface = nativeSurface + 1;
    uint64_t size{};
    // Verified Win64 member ABI: this in RCX and hidden result storage in RDX.
    reinterpret_cast<void *(*)(void *, void *)>(symbols.address[ViewDisplaySize])(view, &size);
    result.width = read<int32_t>(&size, layout.width);
    result.height = read<int32_t>(&size, layout.height);
    require(result.width > 1 && result.height > 1 && result.width <= 32768 && result.height <= 32768,
            "Viewport dimensions are unavailable or exceed bounds");
    auto position = [&](int x, int y, double &mapX, double &mapY) {
        uint64_t pixel{}, map{};
        memcpy(reinterpret_cast<unsigned char *>(&pixel) + layout.pixelX, &x, sizeof(x));
        memcpy(reinterpret_cast<unsigned char *>(&pixel) + layout.pixelY, &y, sizeof(y));
        // PixelPosition is an eight-byte by-value argument in R8, not a pointer.
        reinterpret_cast<void *(*)(void *, void *, uint64_t)>(symbols.address[ViewMapPosition])(view, &map, pixel);
        mapX = std::ldexp(double(read<int32_t>(&map, layout.mapX)), -8);
        mapY = std::ldexp(double(read<int32_t>(&map, layout.mapY)), -8);
    };
    position(0, 0, result.left, result.top);
    position(result.width, result.height, result.right, result.bottom);
    require(result.right > result.left && result.bottom > result.top, "Viewport transform is not ordered");
    if (point && output)
        *output = projectInputWorldPoint(*point, result.width, result.height, [&](int32_t x, int32_t y) {
            InputPoint value{};
            position(x, y, value.x, value.y);
            return value;
        });
    return result;
}

ViewportSnapshot readViewport(const Symbols &symbols, void *player) {
    return viewport(symbols, player, nullptr, nullptr);
}

InputPosition projectWorldInput(const Symbols &symbols, void *player, InputPoint point) {
    InputPosition output{};
    viewport(symbols, player, &point, &output);
    return output;
}
