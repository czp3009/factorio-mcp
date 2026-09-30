// Notify retirement before the unchanged native dispatch. Preserve entry registers, flags, stack arguments and
// complete user XSTATE; tail-transfer after restoring the frame so native exceptions keep their original unwind path.
.text
.globl fm_view_retire_hook
.hidden fm_view_retire_hook
.hidden fm_view_retire_original
.hidden fm_xsave_mode
.hidden fm_xsave_low
.hidden fm_xsave_high
.hidden fm_before_view_retire
.type fm_view_retire_hook,@function
fm_view_retire_hook:
    .cfi_startproc
    endbr64
    pushq %rbp
    .cfi_def_cfa_offset 16
    .cfi_offset %rbp,-16
    movq %rsp,%rbp
    .cfi_def_cfa_register %rbp
    // Capture flags before stack allocation changes them.
    pushfq
    popq -16(%rbp)
    subq $65728,%rsp
    movq %rax,-24(%rbp)
    movq %rcx,-32(%rbp)
    movq %rdx,-40(%rbp)
    movq %rdi,-48(%rbp)
    movq %rsi,-56(%rbp)
    movq %r8,-64(%rbp)
    movq %r9,-72(%rbp)
    movq %r10,-80(%rbp)
    movq %r11,-88(%rbp)
    leaq 63(%rsp),%rdi
    andq $-64,%rdi
    movq %rdi,-96(%rbp)
    cmpl $0,fm_xsave_mode(%rip)
    je .Lview_fxsave
    movq $0,512(%rdi)
    movq $0,520(%rdi)
    movq $0,528(%rdi)
    movq $0,536(%rdi)
    movq $0,544(%rdi)
    movq $0,552(%rdi)
    movq $0,560(%rdi)
    movq $0,568(%rdi)
    movl fm_xsave_low(%rip),%eax
    movl fm_xsave_high(%rip),%edx
    xsave64 (%rdi)
    jmp .Lview_callback
.Lview_fxsave:
    fxsave64 (%rdi)
.Lview_callback:
    movq -48(%rbp),%rdi
    call fm_before_view_retire
    movq -96(%rbp),%rdi
    cmpl $0,fm_xsave_mode(%rip)
    je .Lview_fxrestore
    movl fm_xsave_low(%rip),%eax
    movl fm_xsave_high(%rip),%edx
    xrstor64 (%rdi)
    jmp .Lview_registers
.Lview_fxrestore:
    fxrstor64 (%rdi)
.Lview_registers:
    movq -24(%rbp),%rax
    movq -32(%rbp),%rcx
    movq -40(%rbp),%rdx
    movq -48(%rbp),%rdi
    movq -56(%rbp),%rsi
    movq -64(%rbp),%r8
    movq -72(%rbp),%r9
    movq -80(%rbp),%r10
    movq -88(%rbp),%r11
    pushq -16(%rbp)
    popfq
    leave
    .cfi_def_cfa %rsp,8
    .cfi_restore %rbp
    jmp *fm_view_retire_original(%rip)
    .cfi_endproc
.size fm_view_retire_hook,.-fm_view_retire_hook
.section .note.GNU-stack,"",@progbits
