#pragma once
#include "keyboard_event.h"
#include "input_events.h"
#include <stddef.h>
#include <stdint.h>

// These indices describe the adapter request, never the game's Event discriminator.
#define FM_LINUX_POINTER_PRESS 0
#define FM_LINUX_POINTER_RELEASE 1
#define FM_LINUX_POINTER_MOVE 2
#define FM_LINUX_POINTER_WHEEL 3
#define FM_LINUX_POINTER_ENTER 4
#define FM_LINUX_POINTER_CASES 5
#define FM_LINUX_POINTER_BUTTONS 5
#define FM_LINUX_POINTER_NO_FIELD UINT32_MAX

typedef struct FmLinuxPointerEventCase {
    uint32_t kind;
    uint32_t x;
    uint32_t y;
    uint32_t code;
    uint32_t wheel;
    uint32_t wheelY;
    uint8_t defaults[FM_LINUX_EVENT_BYTES];
    uint8_t initialized[FM_LINUX_EVENT_BYTES];
} FmLinuxPointerEventCase;

typedef struct FmLinuxPointerEventLayout {
    uint32_t extent;
    uint32_t type;
    uint32_t time;
    uint32_t emptyType;
    // Logical order: left, right, middle, X1, X2. Values are resolved from native SDL conversion.
    uint32_t codes[FM_LINUX_POINTER_BUTTONS];
    FmLinuxPointerEventCase cases[FM_LINUX_POINTER_CASES];
} FmLinuxPointerEventLayout;

typedef struct FmLinuxPointerStateLayout {
    uint32_t position;
    uint32_t inWindow;
    // Same logical button order as FmLinuxPointerEventLayout::codes.
    uint32_t masks[FM_LINUX_POINTER_BUTTONS];
} FmLinuxPointerStateLayout;

#ifdef __cplusplus
struct PointerEventRequest {
    uint32_t operation = FM_LINUX_POINTER_MOVE;
    uint32_t button = 0;
    int32_t x = 0;
    int32_t y = 0;
    int32_t wheel = 0;
};

struct PointerStateValue {
    int32_t x = 0;
    int32_t y = 0;
    bool inWindow = false;
};

bool validPointerStateLayout(const FmLinuxMouseStateLayout &owner, const FmLinuxPointerStateLayout &layout);
// Fresh passive reads. Neither the position nor the identity tokens may be retained across native dispatch.
int readPointerState(const FmLinuxMouseStateLayout &owner, const FmLinuxPointerStateLayout &layout,
                     InputStateObjects &objects, PointerStateValue &output);

bool validPointerEventLayout(const FmLinuxPointerEventLayout &layout);
// Writes only the current poll's verified, game-constructed empty Event. No routing, allocation or native calls.
// Position is in the game's client pixel coordinates; button/wheel edges use the freshly observed game cursor.
// All validation precedes mutation. The caller must never replay an event after the pump accepts it.
int writePointerEvent(const FmLinuxPointerEventLayout &layout, void *event, size_t capacity,
                      const PointerEventRequest &request, double timestamp) noexcept;
#endif
