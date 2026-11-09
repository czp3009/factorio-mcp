#pragma once
#include "observation_buffer.h"
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
    FmLinuxObservationBuffer type;
} FmLinuxPrototypeValue;

typedef struct FmLinuxUiIdentity {
    uint32_t available;
    FmLinuxPrototypeValue prototype, quality;
} FmLinuxUiIdentity;

#ifdef __cplusplus
#include "../../nativeMain/native/ui_fields.h"
bool validIdentityLayout(const FmLinuxIdentityLayout &layout);
int readPrototypeIdentity(uintptr_t object, bool quality, const FmLinuxIdentityLayout &layout,
                          FmLinuxPrototypeValue &output, ObservationStrings &strings);
int collectIdentity(uintptr_t widget, const FmLinuxIdentityLayout &layout, FmLinuxUiIdentity &output,
                    ObservationStrings &strings, uint32_t fields = FM_UI_ALL_FIELDS);
#endif
