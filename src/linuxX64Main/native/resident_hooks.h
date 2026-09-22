#pragma once
#include "resident.h"

// Layout matches resident_hooks.S; these are our registers, not game object
// fields.
struct FrFrame {
    uintptr_t r15, r14, r13, r12, r11, r10, r9, r8, rdi, rsi, rbp, rbx, rdx,
        rcx, rax, flags;
    uintptr_t branch, return_address;
};
void resident_prepare_hook(FrPatch &patch, const FrFunctionInfo &function,
                           unsigned id);
void resident_discard_hook(FrPatch &patch);
extern "C" void resident_before();
extern "C" void resident_after();
extern "C" uint64_t resident_xsave_mask;
