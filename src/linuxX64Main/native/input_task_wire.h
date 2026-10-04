#pragma once
#include "linux_ipc.h"
#include <stdint.h>

#include "../../nativeMain/native/input_timeline_wire.h"

// Each call owns independent sealed-size storage. Only immutable entries are copied on admission;
// replacement never reuses a previous caller's progress or terminal result.
typedef struct FmLinuxInputTask {
    uint32_t ownerPid;
    uint32_t targetPid;
    uint32_t stopPrevious;
    uint32_t count;
    uint32_t state;
    uint32_t cancel;
    uint32_t completedEntries;
    uint32_t reserved;
    uint64_t evaluatedTicks;
    char reason[512];
    FmInputTimelineEntry entries[FM_INPUT_ENTRIES];
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
    return fm_ipc_load(&task->completedEntries);
}

static inline uint64_t fm_linux_input_ticks(const FmLinuxInputTask *task) {
    return fm_ipc_load64(&task->evaluatedTicks);
}
