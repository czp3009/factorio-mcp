#pragma once
#include <cstdint>

// The unchanged native entry has its verified receiver in RDI and no stack arguments. Before/after callbacks
// must preserve errno and never throw. Cookie 0 forwards transparently, 1 skips a retired receiver, and any
// other cookie identifies one evaluation. After is called only on normal native return; abort is called during
// exception cleanup on the same evaluating thread, before resuming the original exception's unwind.
extern "C" {
extern uint64_t fm_evaluation_original;
void fm_evaluation_hook();
uint64_t fm_before_input_evaluation(void *receiver, uintptr_t caller) noexcept;
void fm_after_input_evaluation(void *receiver, uint64_t cookie) noexcept;
void fm_abort_input_evaluation(void *receiver, uint64_t cookie) noexcept;
}
