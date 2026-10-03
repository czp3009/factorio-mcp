#pragma once
#include "linux_ipc.h"
#include <stdint.h>

#define FM_LINUX_INPUT_STEPS 256
#define FM_LINUX_INPUT_BUTTONS 8
#define FM_LINUX_INPUT_MOTION 64

typedef struct FmLinuxInputButton {
    uint32_t device;
    uint32_t code;
} FmLinuxInputButton;

typedef struct FmLinuxInputMotion {
    uint32_t tick;
    int32_t x;
    int32_t y;
} FmLinuxInputMotion;

typedef struct FmLinuxInputOperation {
    uint32_t ticks;
    uint32_t count;
    uint32_t hasPosition;
    int32_t x;
    int32_t y;
    int32_t wheel;
    FmLinuxInputButton buttons[FM_LINUX_INPUT_BUTTONS];
    uint32_t motionCount;
    FmLinuxInputMotion motion[FM_LINUX_INPUT_MOTION];
} FmLinuxInputOperation;

// Each call owns independent sealed-size storage. Only immutable operations are copied on admission;
// replacement never reuses a previous caller's progress or terminal result.
typedef struct FmLinuxInputTask {
    uint32_t ownerPid;
    uint32_t targetPid;
    uint32_t stopPrevious;
    uint32_t count;
    uint32_t state;
    uint32_t cancel;
    uint32_t completedOperations;
    uint32_t reserved;
    uint64_t evaluatedTicks;
    char reason[512];
    FmLinuxInputOperation operations[FM_LINUX_INPUT_STEPS];
} FmLinuxInputTask;

static inline uint32_t fm_linux_input_state(const FmLinuxInputTask *task) {
    return fm_ipc_load(&task->state);
}

static inline uint32_t *fm_linux_input_state_word(FmLinuxInputTask *task) {
    return &task->state;
}

static inline void fm_linux_input_cancel(FmLinuxInputTask *task) {
    fm_ipc_store(&task->cancel, 1);
}

static inline uint32_t fm_linux_input_completed(const FmLinuxInputTask *task) {
    return fm_ipc_load(&task->completedOperations);
}

static inline uint64_t fm_linux_input_ticks(const FmLinuxInputTask *task) {
    return fm_ipc_load64(&task->evaluatedTicks);
}
