#pragma once
#include "ui_snapshot.h"
#include <array>
#include <unordered_map>

bool validIconLayout(const FmLinuxIconLayout &layout);

// Snapshot-local references only. No image is dereferenced and no borrowed pointer survives this callback.
class UiIcons {
    const FmLinuxIconLayout &layout;
    std::unordered_map<uintptr_t, int32_t> sources;
    std::unordered_map<uintptr_t, std::pair<FmLinuxObservationBuffer, bool>> names;
    int32_t observe(uintptr_t sprite);

  public:
    explicit UiIcons(const FmLinuxIconLayout &layout);
    int collect(uintptr_t widget, FmLinuxUiNode &node, const FmLinuxIconNamesLayout *namesLayout = nullptr,
                UiSnapshot *storage = nullptr);
};
