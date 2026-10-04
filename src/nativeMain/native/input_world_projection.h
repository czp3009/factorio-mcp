#pragma once
#include "input_sequence.h"
#include <cmath>
#include <stdexcept>

// Invert the game's existing pixel-to-world accessor. No copied camera formula or game offset is needed.
// The bounded search chooses the nearest representable content pixel and rejects offscreen targets.
template <class MapAtPixel>
InputPosition projectInputWorldPoint(InputPoint point, int32_t width, int32_t height, MapAtPixel mapAtPixel) {
    if (!std::isfinite(point.x) || !std::isfinite(point.y) || width <= 1 || height <= 1 || width > 32768 ||
        height > 32768)
        throw std::invalid_argument("Invalid world input projection");
    const auto origin = mapAtPixel(0, 0);
    const auto horizontal = mapAtPixel(width - 1, 0);
    const auto vertical = mapAtPixel(0, height - 1);
    if (horizontal.y != origin.y || vertical.x != origin.x || horizontal.x <= origin.x || vertical.y <= origin.y)
        throw std::runtime_error("Unsupported viewport projection");
    if (point.x < origin.x || point.x > horizontal.x || point.y < origin.y || point.y > vertical.y)
        throw std::out_of_range("World input position is outside the current viewport");
    auto closest = [&](double target, int32_t size, bool x) {
        auto coordinate = [&](int32_t pixel) {
            const auto value = x ? mapAtPixel(pixel, 0) : mapAtPixel(0, pixel);
            return x ? value.x : value.y;
        };
        int32_t low = 0, high = size - 1;
        while (low < high) {
            const auto middle = low + (high - low) / 2;
            if (coordinate(middle) < target)
                low = middle + 1;
            else
                high = middle;
        }
        if (low && std::abs(coordinate(low - 1) - target) <= std::abs(coordinate(low) - target))
            --low;
        return low;
    };
    return {closest(point.x, width, true), closest(point.y, height, false)};
}
