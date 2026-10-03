#pragma once
#include "ui_elements.h"
#include "ui_identity.h"
#include "ui_quality.h"
#include <stdint.h>

#define FM_LINUX_MAX_NODES 4096
#define FM_LINUX_MAX_CHILD_RANGES 8
#define FM_LINUX_TYPE_NAME 256
#define FM_LINUX_TEXT_SIZE 1024
#define FM_LINUX_TEXT_GETTERS 64
#define FM_LINUX_UI_PATH 16
#define FM_LINUX_MAX_OPTIONS 1024
#define FM_LINUX_WIDGET_OPTIONS 64
#define FM_LINUX_OPTION_TEXT 512
#define FM_LINUX_MAX_ICON_REFERENCES 512

typedef struct FmLinuxUiStep {
    int32_t child;
    int32_t position;
    int32_t enabled;
    uint32_t hasText;
    uint32_t hasVisible;
    uint32_t visible;
    char type[160];
    char text[1024];
    char prototypeName[256];
    char prototypeType[160];
} FmLinuxUiStep;

typedef struct FmLinuxUiSelector {
    uint32_t count;
    FmLinuxUiStep path[FM_LINUX_UI_PATH];
} FmLinuxUiSelector;

typedef struct FmLinuxTextGetter {
    uint64_t function;
    uint32_t offset;
    uint32_t objectSize;
} FmLinuxTextGetter;

typedef struct FmLinuxTextLayout {
    uint32_t slot;
    uint32_t data;
    uint32_t length;
    uint32_t count;
    FmLinuxTextGetter getters[FM_LINUX_TEXT_GETTERS];
} FmLinuxTextLayout;

typedef struct FmLinuxMemberFlag {
    uint32_t offset;
    uint32_t width;
    uint64_t mask;
    uint32_t shift;
} FmLinuxMemberFlag;

typedef struct FmLinuxChildRange {
    uint32_t begin;
    uint32_t end;
} FmLinuxChildRange;

typedef struct FmLinuxRectangle {
    int32_t x;
    int32_t y;
    int32_t width;
    int32_t height;
} FmLinuxRectangle;

typedef struct FmLinuxRectangleLayout {
    uint64_t function;
    uint32_t parent;
} FmLinuxRectangleLayout;

typedef struct FmLinuxToggleLayout {
    uint64_t function;
    uint32_t slot;
    uint32_t offset;
    uint32_t objectSize;
    uint32_t tableCount;
    uint64_t tables[1024];
    uint32_t modeOffset;
} FmLinuxToggleLayout;

typedef struct FmLinuxCheckLayout {
    uint64_t table;
    uint32_t offset;
    uint32_t objectSize;
} FmLinuxCheckLayout;

typedef struct FmLinuxSliderLayout {
    uint64_t table;
    uint32_t objectSize;
    uint32_t value;
    uint32_t minimum;
    uint32_t maximum;
    uint32_t step;
} FmLinuxSliderLayout;

typedef struct FmLinuxProgressLayout {
    uint64_t table;
    uint32_t objectSize;
    uint32_t value;
    uint32_t direction;
    uint32_t hasText;
} FmLinuxProgressLayout;

typedef struct FmLinuxDropdownLayout {
    uint64_t table;
    uint32_t objectSize;
    uint32_t selected;
    uint32_t first;
    uint32_t last;
    uint32_t stride;
    uint32_t button;
} FmLinuxDropdownLayout;

typedef struct FmLinuxUiOption {
    uint32_t size;
    uint32_t truncated;
    char text[FM_LINUX_OPTION_TEXT];
} FmLinuxUiOption;

typedef struct FmLinuxSwitchLayout {
    uint64_t table;
    uint32_t objectSize;
    uint32_t state;
    uint32_t allowNone;
} FmLinuxSwitchLayout;

typedef struct FmLinuxNumberLayout {
    uint64_t widgetType;
    uint64_t type;
    uint32_t count;
    uint32_t draw;
    uint32_t zero;
    uint32_t unknown;
    uint32_t infinite;
} FmLinuxNumberLayout;

typedef struct FmLinuxIconLayout {
    uint64_t widgetType;
    uint64_t type;
    uint32_t iconSize;
    uint32_t normal, hovered, disabled;
} FmLinuxIconLayout;

typedef struct FmLinuxUiLayout {
    uint32_t guiSize;
    uint32_t widgetSize;
    uint32_t root;
    uint32_t rangeCount;
    FmLinuxChildRange ranges[FM_LINUX_MAX_CHILD_RANGES];
    FmLinuxMemberFlag enabled;
    FmLinuxMemberFlag destroying;
    FmLinuxMemberFlag visible;
    FmLinuxMemberFlag hiddenBySearch;
    FmLinuxMemberFlag renderEnabled;
    FmLinuxTextLayout text;
    FmLinuxRectangleLayout rectangle;
    FmLinuxToggleLayout toggle;
    uint32_t checkCount;
    FmLinuxCheckLayout checks[64];
    uint32_t sliderCount;
    FmLinuxSliderLayout sliders[64];
    uint32_t progressCount;
    FmLinuxProgressLayout progress[64];
    uint32_t dropdownCount;
    FmLinuxDropdownLayout dropdowns[64];
    uint32_t switchCount;
    FmLinuxSwitchLayout switches[64];
    FmLinuxNumberLayout number;
    FmLinuxIdentityLayout identity;
    FmLinuxElementLayout elements;
    FmLinuxQualityLayout quality;
    FmLinuxIconLayout icons;
} FmLinuxUiLayout;

typedef struct FmLinuxUiNode {
    int32_t parent;
    uint32_t depth;
    uint32_t enabled;
    uint32_t destroying;
    uint32_t visible;
    uint32_t hiddenBySearch;
    uint32_t renderEnabled;
    uint32_t selected;
    uint32_t truncated;
    uint32_t typeTruncated;
    char type[FM_LINUX_TYPE_NAME];
    uint32_t textAvailable;
    uint32_t textTruncated;
    uint32_t textSize;
    uint64_t textTotal;
    char text[FM_LINUX_TEXT_SIZE];
    FmLinuxRectangle bounds;
    uint32_t toggledAvailable;
    uint32_t toggled;
    uint32_t checkAvailable;
    int32_t checkState;
    uint32_t sliderAvailable;
    uint32_t progressAvailable;
    double progressValue;
    uint8_t progressDirection;
    uint8_t progressHasText;
    double value, minimum, maximum, step;
    uint32_t dropdownAvailable;
    int32_t selectedIndex;
    uint32_t optionsAvailable;
    uint32_t optionFirst;
    uint32_t optionCount;
    uint32_t optionTotal;
    uint32_t switchAvailable;
    uint8_t switchState;
    uint8_t switchAllowNone;
    uint32_t numberAvailable;
    uint32_t numberFlags;
    double numberValue;
    uint32_t iconsAvailable;
    FmLinuxUiIdentity identity;
    FmLinuxUiElement element;
    FmLinuxUiQuality quality;
    int32_t iconNormal, iconHovered, iconDisabled;
} FmLinuxUiNode;

typedef struct FmLinuxUiSnapshot {
    uint32_t count;
    uint32_t truncated;
    uint32_t treeTruncated;
    FmLinuxUiNode nodes[FM_LINUX_MAX_NODES];
    uint32_t optionCount;
    FmLinuxUiOption options[FM_LINUX_MAX_OPTIONS];
} FmLinuxUiSnapshot;

#ifdef __cplusplus
class UiTargetReference;

// Borrowed only within one frontend callback. Never store these addresses in IPC or across frames.
struct UiTarget {
    uintptr_t gui = 0;
    uintptr_t root = 0;
    uintptr_t widget = 0;
    FmLinuxRectangle bounds{};
    const UiTargetReference *reference = nullptr;
};

bool validUiLayout(const FmLinuxUiLayout &layout);
int snapshotUi(void *gui, const FmLinuxUiLayout &layout, uint32_t limit, const uint32_t *cancel,
               FmLinuxUiSnapshot &output, const FmLinuxUiSelector *selector = nullptr);
// Selects exactly one widget from a complete traversal and checks its ancestors before action admission.
int selectUiTarget(void *gui, const FmLinuxUiLayout &layout, const FmLinuxUiSelector &selector, const uint32_t *cancel,
                   FmLinuxUiSnapshot &snapshot, UiTarget &output);
// Reacquires the authoritative GUI/root and traverses fresh child ranges after a callback.
// Returns ENOENT for a removed/destroying widget and ESTALE when its GUI/root was replaced.
int liveUiTarget(uintptr_t guiInstance, const FmLinuxUiLayout &layout, const UiTarget &target);
// Revalidates a borrowed target around the native rectangle getter. Used for transferred capture cleanup.
int refreshUiTarget(uintptr_t guiInstance, const FmLinuxUiLayout &layout, UiTarget &target);
#endif
