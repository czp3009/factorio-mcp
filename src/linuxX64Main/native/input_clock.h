#pragma once
#include <stdint.h>

typedef struct FmLinuxInputClockLayout {
    uint32_t guiSize;
    uint32_t member;
    uint32_t handlerSize;
    uint32_t slot;
    uint64_t handlerTable;
    uint64_t function;
} FmLinuxInputClockLayout;

#ifdef __cplusplus
bool validInputClockLayout(const FmLinuxInputClockLayout &layout);
// The current GUI must be borrowed at the frontend safe point. Kotlin must first verify the clock's code/data
// and double-return ABI. Never substitute a different clock when the native association is unavailable.
int readInputClock(void *gui, const FmLinuxInputClockLayout &layout, double &output);
#endif
