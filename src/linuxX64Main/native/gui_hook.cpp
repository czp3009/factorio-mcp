#include "gui_hook.h"
#include <cpuid.h>

extern "C" {
uint64_t fm_gui_original = 0;
uint32_t fm_xsave_mode = 0;
uint32_t fm_xsave_low = 0;
uint32_t fm_xsave_high = 0;
}

bool initializeHookState() {
    unsigned eax, ebx, ecx, edx;
    if (!__get_cpuid(1, &eax, &ebx, &ecx, &edx) || !(edx & bit_FXSAVE) || !(edx & bit_SSE2))
        return false;
    if (!(ecx & bit_OSXSAVE)) {
        fm_xsave_mode = 0;
        return true;
    }
    uint32_t low, high;
    asm volatile("xgetbv" : "=a"(low), "=d"(high) : "c"(0));
    if (!__get_cpuid_count(0xd, 0, &eax, &ebx, &ecx, &edx) || ebx < 512 || ebx > 65536 ||
        (low & ~eax) || (high & ~edx))
        return false;
    fm_xsave_low = low;
    fm_xsave_high = high;
    fm_xsave_mode = 1;
    return true;
}
