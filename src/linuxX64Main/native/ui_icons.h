#pragma once
#include "ui_snapshot.h"
#include <array>

bool validIconLayout(const FmLinuxIconLayout &layout);

// Snapshot-local references only. No image is dereferenced and no borrowed pointer survives this callback.
class UiIcons {
    const FmLinuxIconLayout &layout;
    std::array<uintptr_t, FM_LINUX_MAX_ICON_REFERENCES> sources{};
    uint32_t count = 0;
    int32_t observe(uintptr_t sprite);

  public:
    explicit UiIcons(const FmLinuxIconLayout &layout);
    int collect(uintptr_t widget, FmLinuxUiNode &node);
};
