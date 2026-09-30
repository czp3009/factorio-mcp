// Before/after adapter for a verified receiver-only native input evaluation. No code is relocated.
.text
.globl fm_evaluation_hook
.hidden fm_evaluation_hook
.hidden fm_evaluation_original
.hidden fm_before_input_evaluation
.hidden fm_after_input_evaluation
.hidden fm_abort_input_evaluation
.globl fm_evaluation_return_site
.hidden fm_evaluation_return_site
.hidden fm_xsave_mode
.hidden fm_xsave_low
.hidden fm_xsave_high
#include
"hook_state.inc"

.type fm_evaluation_hook, @function
fm_evaluation_hook:
.cfi_startproc
.cfi_personality 0x9b, DW.ref.__gxx_personality_v0
.cfi_lsda 0x1b, .LevaluationExceptions
.LevaluationBegin:
endbr64
pushq %rbp
.cfi_def_cfa_offset 16
.cfi_offset %rbp, -16
movq %rsp, %rbp
.cfi_def_cfa_register %rbp
pushfq
popq -16(%rbp)
subq $65728,%rsp
movq %rdi, -8(%rbp)
save_hook_state
movq -8(%rbp), %rdi
movq 8(%rbp), %rsi
call fm_before_input_evaluation
movq %rax, -104(%rbp)
cmpq $1, %rax
je .LevaluationSkipped
testq %rax, %rax
je .LevaluationForward
restore_hook_state
.LevaluationCall:
call *fm_evaluation_original(%rip)
fm_evaluation_return_site:
pushfq
popq -16(%rbp)
save_hook_state
movq -8(%rbp), %rdi
movq -104(%rbp), %rsi
call fm_after_input_evaluation
restore_hook_state
.cfi_remember_state
leave
.cfi_def_cfa %rsp, 8
.cfi_restore %rbp
ret
.cfi_restore_state
.LevaluationSkipped:
restore_hook_state
.cfi_remember_state
leave
.cfi_def_cfa %rsp, 8
.cfi_restore %rbp
ret
.cfi_restore_state
.LevaluationForward:
restore_hook_state
.cfi_remember_state
leave
.cfi_def_cfa %rsp, 8
.cfi_restore %rbp
jmp *fm_evaluation_original(%rip)
.cfi_restore_state
.LevaluationUnwind:
// The unwinder supplies the exception in RAX. Notify without swallowing, translating or replaying it.
movq %rax, -112(%rbp)
movq -8(%rbp), %rdi
movq -104(%rbp), %rsi
call fm_abort_input_evaluation
movq -112(%rbp), %rdi
call _Unwind_Resume@PLT
.LevaluationEnd:
.cfi_endproc
.size fm_evaluation_hook, .-fm_evaluation_hook

.section .gcc_except_table, "a", @progbits
.LevaluationExceptions:
.byte 0xff // Omitted landing-pad base: use the function start.
.byte 0xff // No type table: cleanup only.
.byte 0x01 // ULEB128 call-site entries.
.uleb128 .LevaluationSitesEnd-.LevaluationSites
.LevaluationSites:
.uleb128 0
.uleb128 .LevaluationCall-.LevaluationBegin
.uleb128 0
.uleb128 0
.uleb128 .LevaluationCall-.LevaluationBegin
.uleb128 fm_evaluation_return_site-.LevaluationCall
.uleb128 .LevaluationUnwind-.LevaluationBegin
.uleb128 0
.uleb128 fm_evaluation_return_site-.LevaluationBegin
.uleb128 .LevaluationEnd-fm_evaluation_return_site
.uleb128 0
.uleb128 0
.LevaluationSitesEnd:

.hidden DW.ref.__gxx_personality_v0
.weak DW.ref.__gxx_personality_v0
.section .data.DW.ref.__gxx_personality_v0, "awG", @progbits,DW.ref.__gxx_personality_v0, comdat
.p2align 3
.type DW.ref.__gxx_personality_v0, @object
.size DW.ref.__gxx_personality_v0, 8
DW.ref.__gxx_personality_v0:
.quad __gxx_personality_v0
.section .note.GNU-stack, "", @progbits
