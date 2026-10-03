#pragma once
#include "ui_identity.h"
#include <stdint.h>

typedef struct FmLinuxQualityLayout {
    uint64_t widgetType, providerType, registry;
    uint32_t getter, width, quality, comparison;
    uint32_t registrySize, first, last;
} FmLinuxQualityLayout;

typedef struct FmLinuxUiQuality {
    uint32_t available, quality, comparison, lookup, truncated, nameSize;
    char name[FM_LINUX_IDENTITY_TEXT];
} FmLinuxUiQuality;

#ifdef __cplusplus
bool validQualityLayout(const FmLinuxQualityLayout &layout);
int collectQuality(uintptr_t widget, const FmLinuxQualityLayout &layout, const FmLinuxIdentityLayout &identity,
                   FmLinuxUiQuality &output);
#endif
