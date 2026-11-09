#pragma once
#include "ui_snapshot.h"
#include <array>
#include <unordered_map>

bool validIconLayout(const FmLinuxIconLayout &layout);

// Snapshot-local identities. No borrowed pointer survives this callback.
class UiIcons {
    const FmLinuxIconLayout &layout;
    std::unordered_map<uintptr_t, int32_t> sources;
    std::unordered_map<uintptr_t, std::pair<FmLinuxObservationBuffer, bool>> names;
    int32_t observe(uintptr_t sprite);
    int name(uintptr_t sprite, const FmLinuxIconNamesLayout *layout, UiSnapshot *storage,
             FmLinuxObservationBuffer &output, bool &truncated);

  public:
    explicit UiIcons(const FmLinuxIconLayout &layout);
    int collect(uintptr_t widget, FmLinuxUiNode &node, const FmLinuxIconNamesLayout *namesLayout = nullptr,
                UiSnapshot *storage = nullptr);
    int collectImage(uintptr_t widget, uintptr_t widgetType, const FmUiImageLayout &images, FmLinuxUiNode &node,
                     const FmLinuxIconNamesLayout &names, UiSnapshot &storage);
};
