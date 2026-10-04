#pragma once
#include <stdint.h>

#define FM_INPUT_ENTRIES 256
#define FM_INPUT_INTERVALS 64

typedef struct FmInputInterval {
    uint32_t first, last;
} FmInputInterval;

// Fixed, immutable descriptors. Long paths are evaluated without expanding per-tick points.
typedef struct FmInputTimelineEntry {
    uint32_t kind, code, intervalCount, space, tileCenters, perPointTicks;
    double fromX, fromY, toX, toY;
    FmInputInterval intervals[FM_INPUT_INTERVALS];
} FmInputTimelineEntry;
