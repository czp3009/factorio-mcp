#pragma once
#include <stddef.h>
#include <stdint.h>
#include "resident.h"
#define FM_TEXT_CAP (256 * 1024)
enum { FM_LUA_TSTRING = 4, FM_LUA_POP_ONE = -2 };
struct Payload {
    uintptr_t state, load, pcall, tostring, settop, type;
    uintptr_t reader, query;
    uintptr_t dlopen_function, dlsym_function, activate;
    unsigned activate_count;
    int dlopen_flags;
    struct FrConfig resident;
    char prepare_name[64];
    const char *source;
    size_t length;
    int status;
    size_t result_size;
    char name[32], mode[8];
    char result[FM_TEXT_CAP];
};
