#pragma once
#include "bridge.h"

struct ViewportSnapshot {
    uint32_t surface{};
    int32_t width{}, height{};
    double left{}, top{}, right{}, bottom{};
};

ViewportSnapshot readViewport(const Symbols &symbols, void *player);
