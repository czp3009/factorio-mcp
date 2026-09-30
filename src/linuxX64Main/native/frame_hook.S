// Observe before presentation; restore all entry state and tail-transfer to the unchanged native callback.
.text
.globl fm_frame_hook
.hidden fm_frame_hook
.hidden fm_frame_original
.hidden fm_before_frame
.hidden fm_xsave_mode
.hidden fm_xsave_low
.hidden fm_xsave_high
#include
"hook_state.inc"
.type fm_frame_hook, @function
fm_frame_hook:
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
movq 8(%rbp),%rdx
call fm_before_frame
restore_hook_state
leave
.cfi_def_cfa %rsp, 8
.cfi_restore %rbp
jmp *fm_frame_original(%rip)
.cfi_endproc
.size fm_frame_hook, .-fm_frame_hook
.section .note.GNU-stack, "", @progbits
