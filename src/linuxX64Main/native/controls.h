#pragma once
#include <stdint.h>

#define FM_LINUX_MAX_CONTROLS 1024
#define FM_LINUX_CONTROL_ID_BYTES 256

typedef struct FmLinuxControlsLayout {
    uint64_t registry;
    uint64_t guard;
    uint64_t loading;
    uint64_t prototypeVtable;
    uint32_t registrySize;
    uint32_t begin;
    uint32_t end;
    uint32_t size;
    uint32_t nameData;
    uint32_t nameLength;
    uint32_t linked;
    uint32_t custom;
    uint32_t gui;
    uint32_t guiMask;
    uint32_t usage;
    uint32_t slots[2];
    uint32_t type;
    uint32_t code;
    uint32_t modifiers;
    uint32_t prototypeSize;
    uint32_t enabled;
    uint32_t spectating;
    uint32_t cutscene;
} FmLinuxControlsLayout;

typedef struct FmLinuxBinding {
    uint32_t type;
    uint32_t code;
    uint32_t modifiers;
} FmLinuxBinding;

typedef struct FmLinuxControl {
    char id[FM_LINUX_CONTROL_ID_BYTES];
    char linked[FM_LINUX_CONTROL_ID_BYTES];
    char owner[FM_LINUX_CONTROL_ID_BYTES];
    uint32_t custom;
    uint32_t enabled;
    uint32_t spectating;
    uint32_t cutscene;
    uint32_t gui;
    int32_t usage;
    FmLinuxBinding bindings[2];
    FmLinuxBinding effective[2];
} FmLinuxControl;

typedef struct FmLinuxControlsSnapshot {
    uint32_t registryCount;
    uint32_t count;
    uint32_t truncated;
    FmLinuxControl controls[FM_LINUX_MAX_CONTROLS];
} FmLinuxControlsSnapshot;

#ifdef __cplusplus
bool validControlsLayout(const FmLinuxControlsLayout& layout);
// Called only inside the verified frontend safe point. No borrowed pointers survive the call.
int snapshotControls(const FmLinuxControlsLayout& layout, const uint32_t* cancel, FmLinuxControlsSnapshot& output);
#endif
