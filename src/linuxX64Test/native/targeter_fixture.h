#pragma once
#include <cstdint>

struct Targeter;
struct Targetable {
    uintptr_t padding[FIXTURE_PADDING]{};
    Targeter *head = nullptr;
    __attribute__((noinline)) void clear();
};

struct Targeter {
    uintptr_t padding[FIXTURE_PADDING]{};
    Targetable *target = nullptr;
#if FIXTURE_PADDING == 1
    Targeter *previous = nullptr;
    Targeter *next = nullptr;
#else
    Targeter *next = nullptr;
    Targeter *previous = nullptr;
#endif
    __attribute__((noinline)) void attachTo(Targetable *value);
};
