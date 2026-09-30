#pragma once
#include <windows.h>
#include <oleauto.h>
#include <dbghelp.h>
#include <stdint.h>
#include <tlhelp32.h>
#ifdef __cplusplus
extern "C" {
#endif
#define FM_MAX_NODES 4096
#define FM_MAX_OPTIONS 1024
#define FM_MAX_SPRITES 512
#define FM_MAX_WIDGET_OPTIONS 64
#define FM_MAX_INPUT_STEPS 256
#define FM_MAX_INPUT_BUTTONS 8
#define FM_MAX_INPUT_MOTION 64
#define FM_INPUT_NAME_SIZE 128
#define FM_MAX_LUA_SOURCE 131072
#define FM_MAX_QUERY_ARGUMENTS 262144
#define FM_MAX_WORLD_JSON (1024 * 1024)
#define FM_MAX_PATH 16
#define FM_MAX_CONTROLS 1024
#define FM_MAX_IMAGE (16 * 1024 * 1024)
#define FM_MAX_CHAT 256

typedef struct FmInputTransfer {
    uint32_t available, clientPresent, importPresent, segmentIndex, totalSegments;
    uint64_t queuedBatches;
    char reason[256];
} FmInputTransfer;

typedef struct FmChatRecord {
    uint64_t identity, tick;
    uint32_t stream, playerIndex, truncated;
    char text[4096], raw[4096];
} FmChatRecord;

typedef struct FmChatSnapshot {
    uint64_t consoleIdentity, totals[2];
    uint32_t count;
    FmChatRecord records[FM_MAX_CHAT];
} FmChatSnapshot;

typedef struct FmChatRequest {
    uint32_t size;
    char text[4097];
} FmChatRequest;

typedef struct FmStep {
    int32_t child, position, enabled, hasText;
    int32_t visible, hasVisible;
    char type[160], text[1024];
    char prototypeName[256], prototypeType[160];
} FmStep;

typedef struct FmAction {
    int32_t count, kind, button, alt, control, shift;
    double x, y;
    uint32_t textCount, text[1024];
    uint32_t keyCount, keys[8];
    FmStep path[FM_MAX_PATH];
} FmAction;

typedef struct FmNode {
    int32_t depth, enabled;
    int32_t x, y, width, height;
    int32_t truncated, selected;
    char type[160];
    char text[1024];
    uint32_t properties;
    int32_t checkState, toggled, selectedIndex;
    double value, minimum, maximum, valueStep;
    uint32_t optionFirst, optionCount, optionTotal;
    uint32_t identityTruncated;
    char prototypeName[256], prototypeType[160];
    uint32_t numberFlags;
    double numberValue;
    int32_t visible, renderEnabled, hiddenBySearch;
    double progressValue;
    uint32_t progressDirection, progressHasText;
    char qualityName[256], qualityType[160];
    uint32_t elementFlags, elementCount;
    char itemType[160];
    float itemHealth, magazineLeft;
    double durabilityLeft;
    int32_t iconNormal, iconHovered, iconDisabled;
    uint32_t conditionQuality, conditionComparison;
    uint32_t conditionLookup, conditionNameTruncated;
    char conditionName[256];
    uint32_t switchState, switchAllowNone;
} FmNode;

typedef struct FmSprite {
    uint32_t flags;
    char filename[512];
    int16_t x, y, width, height;
    double scale, shiftX, shiftY;
    float tint[4];
    int32_t next, extra;
} FmSprite;

typedef struct FmOption {
    char text[512];
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
    uint32_t imageSize, imageWidth, imageHeight;
    char message[512];
    uint32_t controlCount, registryCount;
    FmControl controls[FM_MAX_CONTROLS];
    FmNode nodes[FM_MAX_NODES];
    uint32_t optionCount;
    FmOption options[FM_MAX_OPTIONS];
    uint32_t spriteCount;
    FmSprite sprites[FM_MAX_SPRITES];
    unsigned char image[FM_MAX_IMAGE];
    uint32_t worldSize;
    char worldJson[FM_MAX_WORLD_JSON];
    FmChatSnapshot chat;
    FmInputTransfer inputTransfer;
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
    SdlTicks,
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
    GuiSend,
    ActionDestroy,
    ActionNoData,
    ActionDataType,
    StringTypeInfo,
    SymbolCount
} FmSymbol;

typedef struct EventLayout {
    uint32_t mouseSize, mouseX, mouseY, wheel, button, eventType, mouseTime, mouseAlt, mouseControl, mouseShift,
        mouseSource, previous;
    uint32_t keySize, unichar, keyTime, keyCode, extKey, key, keyAlt, keyControl, keyShift, keyMeta, handled, keySource;
    uint32_t left, right, middle, enter, down, up, leave, keyNone, keyA, keyBackspace;
    uint32_t widgetFlags, fireOnDown, extKeyNone;
    uint32_t guiCaptureTarget, widgetTargetable;
} EventLayout;

typedef struct ControlLayout {
    uint32_t first, last, slots[2], key, value, linked, custom, gui, usage;
    uint32_t type, code, modifiers;
    uint32_t enabled, spectating, cutscene, stringSize, mouseValue;
} ControlLayout;

typedef struct FrontendInputLayout {
    uint32_t supported, optionalValue, optionalEngaged, eventSize, keyboard, scancode, keyDown, keyUp;
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

typedef struct SpriteLayout {
    uint64_t type;
    uint32_t supported, normal, hovered, disabled, filename;
    uint32_t x, y, width, height, scale, shiftX, shiftY, tint[4], next, extra, empty;
} SpriteLayout;

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
} FmInputEventLayout;

typedef struct TimedInputLayout {
    uint32_t supported;
    FmInputEventLayout events;
    uint32_t globalInputState, globalSource, sourcePlayer, playerMap, mapTick;
} TimedInputLayout;

typedef struct FmWorldQuery {
    uint32_t sourceSize, argumentsSize, includeViewport;
    char source[FM_MAX_LUA_SOURCE], arguments[FM_MAX_QUERY_ARGUMENTS];
} FmWorldQuery;

typedef struct FmInputButton {
    uint32_t device, code;
} FmInputButton;

typedef struct FmInputOperation {
    uint32_t ticks, count, hasPosition;
    int32_t x, y, wheel;
    FmInputButton buttons[FM_MAX_INPUT_BUTTONS];
    uint32_t motionCount;

    struct {
        uint32_t tick;
        int32_t x, y;
    } motion[FM_MAX_INPUT_MOTION];
} FmInputOperation;

// A task owns its own mapping, so replacement cannot overwrite another caller's terminal result.
typedef struct FmInputTask {
    uint32_t stopPrevious, count, ownerPid;
    volatile LONG state, cancel, completedOperations;
    volatile LONG64 evaluatedTicks;
    char reason[512];
    FmInputOperation operations[FM_MAX_INPUT_STEPS];
} FmInputTask;

typedef struct ChatLayout {
    uint32_t supported, console, lists[2], head, count, next, previous, value;
    uint32_t tick, playerIndex, text, cached, stringSize;
    uint32_t sendSupported, contextSize, contextPlayer, actionSize, actionType, actionPlayer, actionBuffer;
    uint32_t writeToConsole;
} ChatLayout;

typedef struct InputTransferLayout {
    uint32_t supported, client, synchronizer, listener, queue;
    uint32_t map, mapSize, first, count, blockSize, vectorSize, vectorBegin, vectorEnd;
    uint32_t segmentSize, actionType, segmentIndex, totalSegments, importAction;
} InputTransferLayout;

typedef struct Symbols {
    uint64_t address[SymbolCount];
    uint64_t prepareEnd, mainEnd;
    uint32_t guiRootOffset, gameMapOffset, mapPausedOffset, mapStopLevelOffset, pauseSupported, widgetParentOffset;
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
    SpriteLayout sprites;
    WorldLayout world;
    TimedInputLayout timedInput;
    ViewportLayout viewport;
    ChatLayout chat;
    InputTransferLayout inputTransfer;
} Symbols;

typedef struct Shared {
    volatile LONG attached, command, cancel, state;
    uint32_t operation, limit;
    FmAction action;
    volatile LONG64 frame;
    FmResult result;
    FmWorldQuery worldQuery;
    FmChatRequest chat;
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
    return InterlockedCompareExchange(&task->completedOperations, 0, 0);
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
