#pragma once
#include "bridge.h"
#include <array>

class IconReferences {
    const Symbols &symbols;
    unsigned count = 0;
    std::array<const void *, FM_MAX_ICON_REFERENCES> sources{};
    int observe(const void *sprite);

  public:
    explicit IconReferences(const Symbols &symbols);
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
