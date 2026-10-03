#pragma once
#include <stdint.h>

#define FM_LINUX_ELEMENT_TEXT 256

typedef struct FmLinuxElementLayout {
    uint64_t widgetType, stackProvider, itemProvider, itemType, toolType, ammoType;
    uint32_t stackGetter, itemGetter;
    uint32_t stackExtent, itemSize, toolSize, ammoSize;
    uint32_t stackItem, count, health, durability, magazine;
} FmLinuxElementLayout;

typedef struct FmLinuxUiElement {
    uint32_t available, flags, count, typeSize;
    float health, magazine;
    double durability;
    char type[FM_LINUX_ELEMENT_TEXT];
} FmLinuxUiElement;

#ifdef __cplusplus
bool validElementLayout(const FmLinuxElementLayout &layout);
int collectElement(uintptr_t widget, const FmLinuxElementLayout &layout, FmLinuxUiElement &output);
#endif
