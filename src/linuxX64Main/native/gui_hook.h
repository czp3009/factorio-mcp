#pragma once
#include <cstdint>

extern "C" {
extern uint64_t fm_gui_original;
extern uint32_t fm_xsave_mode;
extern uint32_t fm_xsave_low;
extern uint32_t fm_xsave_high;
void fm_gui_hook();
void fm_after_gui_logic(void *receiver, uintptr_t caller) noexcept;
}

bool initializeHookState();
