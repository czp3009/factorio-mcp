// System V event-poll adapter. Preserve entry state around optional interception, then preserve original
// return state around the ordinary empty-poll callback. An intercepted poll never enters that callback.
.text
.globl fm_poll_hook
.hidden fm_poll_hook
.hidden fm_poll_original
.hidden fm_xsave_mode
.hidden fm_xsave_low
.hidden fm_xsave_high
.hidden fm_before_poll
.hidden fm_after_empty_poll

#include "hook_state.inc"

.type fm_poll_hook, @function
fm_poll_hook:
.cfi_startproc
endbr64
pushq %rbp
.cfi_def_cfa_offset 16
.cfi_offset %rbp,-16
movq %rsp, %rbp
.cfi_def_cfa_register %rbp
pushfq
popq -16(%rbp)
// 65536 bytes of XSAVE storage, alignment slack and metadata; keep call alignment at 16 bytes.
subq $65728, %rsp
movq %rdi, -8(%rbp)
movq %rsi, -104(%rbp)
save_hook_state
movq -8(%rbp), %rdi
movq -104(%rbp), %rsi
movq 8(%rbp), %rdx
// The caller's call-time RSP, independent of whether it keeps an RBP frame pointer.
leaq 16(%rbp), %rcx
call fm_before_poll
testl %eax, %eax
js .Lforward
// The callback contract permits only the native bool results 0 and 1.
movl %eax, %eax
movq %rax, -24(%rbp)
restore_hook_state
.cfi_remember_state
leave
.cfi_def_cfa %rsp, 8
.cfi_restore %rbp
ret
.cfi_restore_state
.Lforward:
restore_hook_state
call *fm_poll_original(%rip)
pushfq
popq -16(%rbp)
save_hook_state
// A real event and every original return register remain untouched on a successful poll.
cmpb $0, -24(%rbp)
jne .Lreturn
movq -8(%rbp), %rdi
movq -104(%rbp), %rsi
movq 8(%rbp), %rdx
leaq 16(%rbp), %rcx
call fm_after_empty_poll
movb %al, -24(%rbp)
.Lreturn:
restore_hook_state
leave
.cfi_def_cfa %rsp, 8
.cfi_restore %rbp
ret
.cfi_endproc
.size fm_poll_hook, .-fm_poll_hook
.section .note.GNU-stack, "", @progbits
