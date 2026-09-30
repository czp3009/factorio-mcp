#pragma once
#include <stdint.h>

// Synthetic fixture storage, not a game layout or a resident wire contract.
typedef struct FmIpcFixture {
    uint32_t state;
    uint32_t cancel;
    uint64_t request;
    uint64_t result;
    uint32_t executions;
} FmIpcFixture;
