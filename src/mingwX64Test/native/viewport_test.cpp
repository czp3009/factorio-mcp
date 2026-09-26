#include "viewport.h"
#include <cassert>
#include <cstring>
#include <stdexcept>

namespace {
struct View {
    uint64_t padding{123};
    uint32_t surface{7};
    int32_t x{2560}, y{-5120};
} view;

int player{};
bool absent{};

void *getView(void *receiver) {
    assert(receiver == &player);
    return absent ? nullptr : &view;
}

void *size(void *receiver, void *output) {
    assert(receiver == &view);
    // Deliberately reverse the field order to exercise metadata-derived offsets.
    const int32_t fields[]{600, 800};
    memcpy(output, fields, sizeof(fields));
    return output;
}

void *map(void *receiver, void *output, uint64_t packed) {
    assert(receiver == &view);
    int32_t pixel[2];
    memcpy(pixel, &packed, sizeof(pixel));
    const int32_t result[]{view.y + pixel[0] * 8, view.x + pixel[1] * 8};
    memcpy(output, result, sizeof(result));
    return output;
}
} // namespace

int main() {
    Symbols symbols{};
    symbols.viewport = {1, 4, 0, 4, 0, 4, 0, offsetof(View, surface), offsetof(View, x), offsetof(View, y)};
    symbols.address[PlayerGameView] = reinterpret_cast<uintptr_t>(getView);
    symbols.address[ViewDisplaySize] = reinterpret_cast<uintptr_t>(size);
    symbols.address[ViewMapPosition] = reinterpret_cast<uintptr_t>(map);
    const auto value = readViewport(symbols, &player);
    assert(value.surface == 8 && value.width == 800 && value.height == 600);
    assert(value.left == 10 && value.top == -20 && value.right == 35 && value.bottom == -1.25);
    auto rejected = [&] {
        try {
            readViewport(symbols, &player);
            return false;
        } catch (const std::runtime_error &) {
            return true;
        }
    };
    absent = true;
    assert(rejected());
    absent = false;
    view.surface = 0;
    assert(readViewport(symbols, &player).surface == 1);
    view.surface = UINT32_MAX;
    assert(rejected());
    view.surface = 7;
    view.x = INT32_MAX;
    assert(rejected());
    view.x = 2560;
    symbols.viewport.supported = 0;
    assert(rejected());
}
