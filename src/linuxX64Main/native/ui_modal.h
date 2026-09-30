#pragma once
#include "ui_snapshot.h"

typedef struct FmLinuxModalLayout {
    uint32_t guiSize;
    uint32_t widgetSize;
    uint32_t begin;
    uint32_t end;
    uint32_t stride;
    uint32_t target;
    uint32_t widgetTargetable;
    uint32_t parent;
} FmLinuxModalLayout;

#ifdef __cplusplus
bool validModalLayout(const FmLinuxModalLayout &modal, const FmLinuxUiLayout &ui);
// Admission at the same safe point as selection. Reads the native stack without pruning its empty records.
int checkUiModal(uintptr_t guiInstance, const FmLinuxUiLayout &ui, const FmLinuxModalLayout &modal,
                 const UiTarget &target);
int selectUiActionTarget(uintptr_t guiInstance, void *gui, const FmLinuxUiLayout &ui,
                         const FmLinuxModalLayout &modal, const FmLinuxUiSelector &selector,
                         const uint32_t *cancel, FmLinuxUiSnapshot &snapshot, UiTarget &output);
#endif
