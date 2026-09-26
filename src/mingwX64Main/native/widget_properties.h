#pragma once
#include "bridge.h"
#include <array>

class SpriteSnapshot {
    const Symbols &symbols;
    FmResult &result;
    std::array<const void *, FM_MAX_SPRITES> sources{};
    int observe(const void *sprite, unsigned depth);

  public:
    SpriteSnapshot(const Symbols &symbols, FmResult &result);
    void collect(void *widget, FmNode &node);
};

void collectWidgetProperties(const Symbols &symbols, void *widget, FmNode &node, FmResult *result = nullptr);
void collectSlotIdentity(const Symbols &symbols, void *widget, FmNode &node);
void collectQualityCondition(const Symbols &symbols, void *widget, FmNode &node);
void collectNumber(const Symbols &symbols, void *widget, FmNode &node);
void collectVisibility(const Symbols &symbols, void *widget, FmNode &node);
void collectProgress(const Symbols &symbols, void *widget, FmNode &node);
void collectSwitch(const Symbols &symbols, void *widget, FmNode &node);
void collectElement(const Symbols &symbols, void *widget, FmNode &node);
