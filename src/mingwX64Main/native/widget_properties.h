#pragma once
#include "observation_storage.h"
#include "../../nativeMain/native/ui_fields.h"
#include <unordered_map>

class IconReferences {
    const Symbols &symbols;
    std::unordered_map<const void *, int> sources;
    std::unordered_map<const void *, std::pair<FmObservationBuffer, bool>> names;
    int observe(const void *sprite);
    std::pair<FmObservationBuffer, bool> name(const void *sprite, ObservationStorage *storage);

  public:
    explicit IconReferences(const Symbols &symbols);
    void collect(void *widget, FmNode &node, ObservationStorage *storage = nullptr);
    void collectImage(void *widget, FmNode &node, ObservationStorage &storage);
};

void collectWidgetProperties(const Symbols &symbols, void *widget, FmNode &node, FmResult *result = nullptr,
                             ObservationStorage *storage = nullptr, const volatile LONG *cancel = nullptr,
                             uint32_t fields = FM_UI_ALL_FIELDS);
void collectSlotIdentity(const Symbols &symbols, void *widget, FmNode &node, ObservationStorage &storage,
                         uint32_t fields = FM_UI_ALL_FIELDS);
void collectQualityCondition(const Symbols &symbols, void *widget, FmNode &node);
void collectNumber(const Symbols &symbols, void *widget, FmNode &node);
void collectVisibility(const Symbols &symbols, void *widget, FmNode &node);
void collectProgress(const Symbols &symbols, void *widget, FmNode &node);
void collectSwitch(const Symbols &symbols, void *widget, FmNode &node);
void collectElement(const Symbols &symbols, void *widget, FmNode &node, ObservationStorage &storage);
void collectTooltip(const Symbols &symbols, void *widget, FmNode &node, ObservationStorage &storage);
void collectGraph(const Symbols &symbols, void *widget, FmNode &node, ObservationStorage &storage,
                  const FmUiGraphRequest &request, const volatile LONG *cancel);
void collectAchievement(const Symbols &symbols, void *widget, FmNode &node);
const void *readWidgetText(const Symbols &symbols, void *widget);
