#pragma once
#include <stdint.h>

#define FM_LINUX_IDENTITY_TEXT 256

typedef struct FmLinuxIdentityLayout {
    uint64_t widgetType, providerType;
    uint32_t prototypeSlot, qualitySlot;
    uint32_t name, minimumExtent;
    uint32_t qualityBase, qualitySize;
    uint32_t stringSize, stringData, stringLength;
} FmLinuxIdentityLayout;

typedef struct FmLinuxPrototypeValue {
    uint32_t flags;
    uint32_t nameSize, typeSize;
    char name[FM_LINUX_IDENTITY_TEXT];
    char type[FM_LINUX_IDENTITY_TEXT];
} FmLinuxPrototypeValue;

typedef struct FmLinuxUiIdentity {
    uint32_t available;
    FmLinuxPrototypeValue prototype, quality;
} FmLinuxUiIdentity;

#ifdef __cplusplus
bool validIdentityLayout(const FmLinuxIdentityLayout &layout);
int readPrototypeIdentity(uintptr_t object, bool quality, const FmLinuxIdentityLayout &layout,
                          FmLinuxPrototypeValue &output);
int collectIdentity(uintptr_t widget, const FmLinuxIdentityLayout &layout, FmLinuxUiIdentity &output);
#endif
