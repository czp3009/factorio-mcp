#pragma once
#include <cstdint>

extern "C" {
extern uint64_t fm_poll_original;
void fm_poll_hook();
// -1 forwards unchanged, 0/1 bypass the native poll with that bool result. Both callers must be validated
// before borrowing the event. This is separate from injection after a naturally empty poll below.
int fm_before_poll(void *receiver, void *event, uintptr_t caller, uintptr_t callerStack) noexcept;
// Called only after a false native poll. The callback validates both callers before using callerStack.
bool fm_after_empty_poll(void *receiver, void *event, uintptr_t caller, uintptr_t callerStack) noexcept;
}
