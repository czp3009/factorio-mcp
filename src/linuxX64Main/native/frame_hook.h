#pragma once
#include <cstdint>

extern "C" {
extern uint64_t fm_frame_original;
void fm_frame_hook();
void fm_before_frame(void *device, void *window, uintptr_t caller) noexcept;
}
