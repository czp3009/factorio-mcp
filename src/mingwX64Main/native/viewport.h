#pragma once
#include "bridge.h"
#include "../../nativeMain/native/input_sequence.h"

struct ViewportSnapshot {
    uint32_t surface{};
    int32_t width{}, height{};
    double left{}, top{}, right{}, bottom{};
};

ViewportSnapshot readViewport(const Symbols &symbols, void *player);
InputPosition projectWorldInput(const Symbols &symbols, void *player, InputPoint point);
