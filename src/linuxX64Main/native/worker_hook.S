// Observe native worker completion, then tail-forward with entry registers, stack arguments and XSTATE intact.
.text
.globl fm_worker_completion_hook
.hidden fm_worker_completion_hook
.hidden fm_worker_completion_original
.hidden fm_before_worker_completion
.hidden fm_xsave_mode
.hidden fm_xsave_low
.hidden fm_xsave_high
#include "hook_state.inc"
.type fm_worker_completion_hook, @function
fm_worker_completion_hook:
    .cfi_startproc
    endbr64
    pushq %rbp
    .cfi_def_cfa_offset 16
    .cfi_offset %rbp, -16
    movq %rsp, %rbp
    .cfi_def_cfa_register %rbp
    pushfq
    popq -16(%rbp)
    subq $65728, %rsp
    save_hook_state
    movq -48(%rbp), %rdi
    movq -56(%rbp), %rsi
    movq 8(%rbp), %rdx
    call fm_before_worker_completion
    restore_hook_state
    leave
    .cfi_def_cfa %rsp, 8
    .cfi_restore %rbp
    jmp *fm_worker_completion_original(%rip)
    .cfi_endproc
.size fm_worker_completion_hook, .-fm_worker_completion_hook
.section .note.GNU-stack, "", @progbits
