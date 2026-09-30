// System V frontend adapter. Preserve the original callee's return registers and complete user XSTATE.
.text
.globl fm_gui_hook
.hidden fm_gui_hook
.hidden fm_gui_original
.hidden fm_xsave_mode
.hidden fm_xsave_low
.hidden fm_xsave_high
.hidden fm_after_gui_logic
.type fm_gui_hook,@function
fm_gui_hook:
    .cfi_startproc
    endbr64
    pushq %rbp
    .cfi_def_cfa_offset 16
    .cfi_offset %rbp,-16
    movq %rsp,%rbp
    .cfi_def_cfa_register %rbp
    // 65536 bytes of XSAVE storage, alignment slack and metadata; keep call alignment at 16 bytes.
    subq $65728,%rsp
    movq %rdi,-8(%rbp)
    call *fm_gui_original(%rip)
    pushfq
    popq -16(%rbp)
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
    je .Lfxsave
    // XSAVE does not initialize every reserved header byte. XRSTOR requires them to be zero.
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
    jmp .Lcallback
.Lfxsave:
    fxsave64 (%rdi)
.Lcallback:
    movq -8(%rbp),%rdi
    movq 8(%rbp),%rsi
    call fm_after_gui_logic
    movq -96(%rbp),%rdi
    cmpl $0,fm_xsave_mode(%rip)
    je .Lfxrestore
    movl fm_xsave_low(%rip),%eax
    movl fm_xsave_high(%rip),%edx
    xrstor64 (%rdi)
    jmp .Lregisters
.Lfxrestore:
    fxrstor64 (%rdi)
.Lregisters:
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
    ret
    .cfi_endproc
.size fm_gui_hook,.-fm_gui_hook
.section .note.GNU-stack,"",@progbits
