#pragma once
#include <windows.h>
#include <oleauto.h>
#include <dbghelp.h>
#include <stdint.h>
#include <tlhelp32.h>
#include "../../nativeMain/native/input_timeline_wire.h"
#include "../../nativeMain/native/rendering_state.h"
#include "../../nativeMain/native/rendering_control.h"
#include "../../nativeMain/native/gui_observation.h"
#include "../../nativeMain/native/request_storage.h"
#include "../../nativeMain/native/widget_walk.h"
#include "../../nativeMain/native/ui_media.h"
#include "../../nativeMain/native/ui_graph.h"
#include "../../nativeMain/native/ui_stored_identity.h"
#include "../../nativeMain/native/text_slice.h"
#include "../../nativeMain/native/binding_query.h"
#include "../../nativeMain/native/chat_observation.h"
#ifdef __cplusplus
extern "C" {
#endif
#define FM_INPUT_NAME_SIZE 128
#define FM_UI_TEXT_CHARACTERS 16384

typedef struct FmObservationBuffer {
    uint64_t address, size;
} FmObservationBuffer;

typedef struct FmChatRecord {
    uint64_t identity, tick;
    uint32_t stream, playerIndex, truncated;
    FmObservationBuffer text, raw, playerName;
    FmTextRange textRange;
    uint32_t textIncluded;
} FmChatRecord;

typedef struct FmChatSnapshot {
    uint64_t consoleIdentity, totals[2];
    uint32_t count;
    FmObservationBuffer records;
} FmChatSnapshot;

typedef struct FmChatRequest {
    uint32_t size;
    char text[4097];
} FmChatRequest;

typedef struct FmAction {
    int32_t count, kind, button, alt, control, shift;
    double x, y;
    uint32_t textCount, text[FM_UI_TEXT_CHARACTERS];
    uint32_t keyCount, keys[8];
    FmRequestBuffer path;
    uint32_t collection, optionOffset, optionLimit;
    uint32_t fields;
    int32_t maxDepth;
    FmTextSliceRequest textSlice;
    FmUiGraphRequest graph;
} FmAction;

typedef struct FmNode {
    int32_t depth, enabled, destroying;
    uint32_t siblingIndex, childrenTruncated;
    int32_t x, y, width, height;
    int32_t truncated, selected;
    FmObservationBuffer type;
    FmObservationBuffer text;
    uint32_t properties;
    int32_t checkState, toggled, selectedIndex, toggleMode;
    int64_t guiIndex;
    double value, minimum, maximum, valueStep;
    uint32_t optionFirst, optionCount, optionTotal, optionOffset;
    uint32_t identityTruncated;
    char prototypeName[256];
    FmObservationBuffer prototypeType;
    uint32_t numberFlags;
    double numberValue;
    int32_t visible, renderEnabled, hiddenBySearch;
    double progressValue;
    uint32_t progressDirection, progressHasText;
    char qualityName[256];
    FmObservationBuffer qualityType;
    uint32_t elementFlags, elementCount;
    FmObservationBuffer itemType;
    float itemHealth, magazineLeft;
    double durabilityLeft;
    int32_t iconNormal, iconHovered, iconDisabled;
    FmObservationBuffer iconNames[3];
    uint32_t iconNameTruncated;
    uint32_t conditionQuality, conditionComparison;
    uint32_t conditionLookup, conditionNameTruncated;
    char conditionName[256];
    uint32_t switchState, switchAllowNone;
    uint32_t tooltipStatus, tooltipTruncated;
    FmObservationBuffer tooltipTitle, tooltipText;
    FmUiImage image;
    FmUiGraph graph;
    FmUiAchievement achievement;
} FmNode;

typedef struct FmOption {
    FmObservationBuffer text;
    uint32_t truncated;
} FmOption;

typedef struct FmBinding {
    uint32_t type, code, modifiers;
    char modifierExpression[128];
} FmBinding;

typedef struct FmControl {
    int32_t custom, enabled, spectating, cutscene, gui, usage;
    char id[256], linked[256], bindingOwner[256];
    FmBinding bindings[2], effective[2];
    uint32_t mouseCodes[5];
} FmControl;

typedef struct FmResult {
    int32_t error, state, attached, count, truncated, paused;
    uint64_t frame;
    uint32_t imageWidth, imageHeight;
    char message[512];
    uint32_t controlCount, registryCount;
    FmObservationBuffer controls, nodes;
    uint32_t optionCount;
    FmObservationBuffer options;
    FmObservationBuffer framePixels;
    uint32_t worldSize;
    FmObservationBuffer worldJson;
    uint32_t guiStatus;
    char guiMessage[512];
    FmChatSnapshot chat;
    FmTextRange textRange;
    FmBindingSelection bindingSelection;
} FmResult;

typedef enum FmSymbol {
    UpdateGui,
    Prepare,
    GuiInstance,
    Global,
    GetGui,
    Walk,
    GetGame,
    Loading,
    TypeId,
    TypeName,
    Text,
    LabelText,
    TextBoxText,
    StringData,
    StringSize,
    WidgetRectangle,
    Enabled,
    Destroying,
    MainStep,
    Present,
    GlSwap,
    DispatchEnter,
    DispatchDown,
    DispatchUp,
    DispatchLeave,
    ModalChild,
    DynamicCast,
    WidgetType,
    LabelType,
    TextBoxType,
    FocusWidget,
    TextKeyDown,
    DispatchClick,
    RelativeMouseEvent,
    ReleaseMouseCapture,
    ControlList,
    ControlsLoading,
    StringDestroy,
    MouseLeft,
    MouseRight,
    MouseMiddle,
    Mouse4,
    Mouse5,
    BindingModifiers,
    NextEvent,
    ProcessEvents,
    EventConstructor,
    SdlTicksNS,
    ToggleButtonType,
    ButtonType,
    DropDownType,
    SliderType,
    LocalPlayer,
    LuaContext,
    DefaultScript,
    LuaStateCast,
    LuaGetTop,
    LuaSetTop,
    LuaCheckStack,
    LuaLoadBuffer,
    LuaProtectedCall,
    LuaToString,
    LuaPushNumber,
    LuaPushString,
    EventDestructor,
    InputStateUpdate,
    InputStatePostUpdate,
    ProcessInputEvent,
    SendInputStateChanges,
    GameDestructor,
    PlayerGameView,
    ViewDisplaySize,
    ViewMapPosition,
    LocalisedRaw,
    ChatStringConstructor,
    PlayerSend,
    ActionDestroy,
    ActionNoData,
    ActionDataType,
    StringTypeInfo,
    HeadlessMode,
    BadFunctionCall,
    SymbolCount
} FmSymbol;

typedef struct EventLayout {
    uint32_t mouseSize, mouseX, mouseY, wheel, button, eventType, mouseTime, mouseAlt, mouseControl, mouseShift,
        mouseSource, previous;
    uint32_t keySize, unichar, keyTime, keyCode, extKey, key, keyAlt, keyControl, keyShift, keyMeta, handled, keySource;
    uint32_t left, right, middle, enter, down, click, up, leave, keyNone, keyA, keyBackspace;
    uint32_t widgetFlags, fireOnDown, extKeyNone;
    uint32_t guiCaptureTarget, widgetTargetable;
} EventLayout;

typedef struct ControlLayout {
    uint32_t first, last, slots[2], key, value, linked, custom, gui, usage;
    uint32_t type, code, modifiers;
    uint32_t enabled, spectating, cutscene, stringSize, mouseValue;
} ControlLayout;

typedef struct KeyboardModifierLayout {
    uint32_t event, state, keys[12];
} KeyboardModifierLayout;

typedef struct FrontendInputLayout {
    uint32_t supported, optionalValue, optionalEngaged, eventSize, keyboard, scancode, keyDown, keyUp;
    uint32_t globalInputState;
    KeyboardModifierLayout modifiers;
} FrontendInputLayout;

typedef struct WidgetPropertyLayout {
    uint32_t supported, checkState, toggleMode, toggled, selectedIndex, value, minimum, maximum, valueStep;
    uint32_t optionsSupported, optionFirst, optionLast, optionStride, optionButton, optionText;
} WidgetPropertyLayout;

typedef struct SlotIdentityLayout {
    uint64_t type;
    uint32_t supported, name, qualityName, prototype, quality;
} SlotIdentityLayout;

typedef struct QualityConditionLayout {
    uint64_t type, registry;
    uint32_t supported, size, getter, quality, comparison;
    uint32_t first, last, name;
} QualityConditionLayout;

typedef struct NumberLayout {
    uint64_t type;
    uint32_t supported, count, draw, zero, unknown, infinite;
} NumberLayout;

typedef struct VisibilityLayout {
    uint32_t supported, flags, visible, render, hiddenBySearch;
} VisibilityLayout;

typedef struct ProgressLayout {
    uint64_t type;
    uint32_t supported, value, direction, hasText;
} ProgressLayout;

typedef struct SwitchLayout {
    uint64_t type;
    uint32_t supported, state, allowNone;
} SwitchLayout;

typedef struct ElementLayout {
    uint64_t stackProvider, itemProvider, itemType, toolType, ammoType;
    uint32_t supported, stackGetter, itemGetter, stackItem, count, health, durability, magazine;
} ElementLayout;

typedef struct IconLayout {
    uint64_t type;
    uint32_t supported, normal, hovered, disabled;
} IconLayout;

typedef struct FmTooltipLayout {
    uint64_t baseType, plainType;
    uint32_t creator, title, text;
} FmTooltipLayout;

typedef struct FmIconNamesLayout {
    uint64_t lookup;
    uint32_t registry, stringSize;
} FmIconNamesLayout;

typedef struct WorldLayout {
    uint32_t supported, scenario, luaState, setupFinished, runningOnLoad, playerIndex;
} WorldLayout;

typedef struct ViewportLayout {
    uint32_t supported, pixelX, pixelY, width, height, mapX, mapY, surfaceIndex, cachedX, cachedY;
} ViewportLayout;

typedef struct FmInputEventLayout {
    uint32_t eventSize, scancode, mouseX, mouseY, mouseButton;
    uint32_t stateSize, stateMouseX, stateMouseY;
    int32_t keyDown, keyUp, mouseMove, mouseDown, mouseUp;
    uint32_t mouseWheel, mouseWheelY;
    int32_t wheel;
    uint32_t stateMouseInWindow;
    int32_t mouseEnter;
    KeyboardModifierLayout modifiers;
} FmInputEventLayout;

typedef struct TimedInputLayout {
    uint32_t supported;
    FmInputEventLayout events;
    uint32_t globalInputState, globalSource, sourcePlayer, playerMap, mapTick;
} TimedInputLayout;

typedef struct FmWorldQuery {
    uint32_t includeViewport;
    FmRequestBuffer source, arguments;
} FmWorldQuery;

// A task owns its own mapping, so replacement cannot overwrite another caller's terminal result.
typedef struct FmInputTask {
    uint32_t stopPrevious, count, ownerPid;
    volatile LONG state, cancel, completedEntries;
    volatile LONG64 evaluatedTicks;
    char reason[512];
    FmInputTimelineEntry entries[FM_INPUT_ENTRIES];
} FmInputTask;

typedef struct ChatLayout {
    uint32_t supported, console, lists[2], head, count, next, previous, value;
    uint32_t tick, playerIndex, text, cached, stringSize, playerName;
    uint32_t sendSupported, actionSize, actionType, actionPlayer, actionBuffer;
    uint32_t writeToConsole;
    uint32_t gameHandler, handlerInputType, replayInput;
} ChatLayout;

typedef struct Symbols {
    uint64_t address[SymbolCount];
    uint64_t prepareEnd, mainEnd;
    uint32_t guiRootOffset, gameMapOffset, mapPausedOffset, mapStopLevelOffset, pauseSupported, widgetSize,
        widgetParentOffset;
    FmUiChildRange widgetChildren[2];
    EventLayout events;
    ControlLayout controls;
    uint64_t processEventsEnd;
    FrontendInputLayout input;
    WidgetPropertyLayout properties;
    SlotIdentityLayout slots;
    QualityConditionLayout conditions;
    SwitchLayout switches;
    NumberLayout numbers;
    VisibilityLayout visibility;
    ProgressLayout progress;
    ElementLayout elements;
    IconLayout icons;
    FmTooltipLayout tooltips;
    FmGuiRegistryLayout guiRegistry;
    FmIconNamesLayout iconNames;
    FmUiImageLayout images;
    FmUiGraphLayout graph;
    FmUiStoredPrototypeLayout connectionPrototype, achievementPrototype;
    FmUiAchievementLayout achievement;
    WorldLayout world;
    TimedInputLayout timedInput;
    ViewportLayout viewport;
    ChatLayout chat;
    FmRenderingState rendering;
    FmRenderingControl renderingControl;
} Symbols;

typedef struct Shared {
    volatile LONG attached, command, cancel, state;
    uint32_t operation, limit, renderingEnabled;
    uint32_t requestSize;
    FmRequestBuffer request;
    FmAction action;
    volatile LONG64 frame;
    FmResult result;
    FmWorldQuery worldQuery;
    FmRequestBuffer bindingQuery;
    FmChatRequest chat;
    FmChatReadOptions chatRead;
    char inputName[FM_INPUT_NAME_SIZE];
    volatile LONG inputActive, inputCancel;
} Shared;

typedef struct Bootstrap {
    Symbols symbols;
} Bootstrap;

static inline int fm_take_result(Shared *state) {
    return InterlockedCompareExchange(&state->command, 0, 3) == 3;
}

static inline void fm_submit(Shared *state) {
    InterlockedExchange(&state->command, 1);
}

static inline void fm_cancel_request(Shared *state) {
    InterlockedExchange(&state->cancel, 1);
}

static inline LONG fm_input_state(FmInputTask *task) {
    return InterlockedCompareExchange(&task->state, 0, 0);
}

static inline void fm_input_cancel(FmInputTask *task) {
    InterlockedExchange(&task->cancel, 1);
}

static inline LONG fm_input_completed(FmInputTask *task) {
    return InterlockedCompareExchange(&task->completedEntries, 0, 0);
}

static inline LONG64 fm_input_ticks(FmInputTask *task) {
    return InterlockedCompareExchange64(&task->evaluatedTicks, 0, 0);
}

static inline void fm_cancel_input(Shared *state) {
    InterlockedExchange(&state->inputCancel, 1);
}

static inline LONG fm_input_active(Shared *state) {
    return InterlockedCompareExchange(&state->inputActive, 0, 0);
}

static inline HMODULE fm_module_for_address(void *address) {
    HMODULE module = NULL;
    GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                       (LPCWSTR)address, &module);
    return module;
}

static inline int fm_type_value(HANDLE process, uint64_t base, uint32_t type, int32_t *result) {
    VARIANT value;
    memset(&value, 0, sizeof(value));
    if (!SymGetTypeInfo(process, base, type, TI_GET_VALUE, &value))
        return 0;
    if (V_VT(&value) == VT_UI1)
        *result = V_UI1(&value);
    else if (V_VT(&value) == VT_I1)
        *result = V_I1(&value);
    else if (V_VT(&value) == VT_UI2)
        *result = V_UI2(&value);
    else if (V_VT(&value) == VT_I2)
        *result = V_I2(&value);
    else if (V_VT(&value) == VT_I4 || V_VT(&value) == VT_UI4)
        *result = V_I4(&value);
    else
        return 0;
    return 1;
}
#ifdef __cplusplus
}
#endif
