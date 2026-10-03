#pragma once
#include "poll_site.h"
#include "key_state.h"
#include "mouse_input_event.h"

// Resolved from the selected executable and verified against its loaded code/data before admission.
typedef struct FmLinuxInputDispatchConfig {
    FmLinuxPollHookConfig site;
    FmLinuxMouseStateLayout owner;
    FmLinuxKeyStateLayout keys;
    FmLinuxKeyboardEventLayout keyboard;
    FmLinuxPointerEventLayout pointer;
    FmLinuxPointerStateLayout pointerState;
    FmLinuxEventClock clock;
    uint64_t pump;
    uint32_t pumpArgument;
} FmLinuxInputDispatchConfig;
