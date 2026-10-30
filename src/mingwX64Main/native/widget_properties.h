#pragma once
#include "observation_storage.h"
#include <unordered_map>

class IconReferences {
    const Symbols &symbols;
    std::unordered_map<const void *, int> sources;
    std::unordered_map<const void *, std::pair<FmObservationBuffer, bool>> names;
    int observe(const void *sprite);

  public:
    explicit IconReferences(const Symbols &symbols);
    void collect(void *widget, FmNode &node, ObservationStorage *storage = nullptr);
};

void collectWidgetProperties(const Symbols &symbols, void *widget, FmNode &node, FmResult *result = nullptr,
                             ObservationStorage *storage = nullptr, const volatile LONG *cancel = nullptr);
void collectSlotIdentity(const Symbols &symbols, void *widget, FmNode &node);
void collectQualityCondition(const Symbols &symbols, void *widget, FmNode &node);
void collectNumber(const Symbols &symbols, void *widget, FmNode &node);
void collectVisibility(const Symbols &symbols, void *widget, FmNode &node);
void collectProgress(const Symbols &symbols, void *widget, FmNode &node);
void collectSwitch(const Symbols &symbols, void *widget, FmNode &node);
void collectElement(const Symbols &symbols, void *widget, FmNode &node);
void collectTooltip(const Symbols &symbols, void *widget, FmNode &node, ObservationStorage &storage);
const void *readWidgetText(const Symbols &symbols, void *widget);
