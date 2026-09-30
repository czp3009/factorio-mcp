// Flags must be saved before any flag-changing instruction at the relevant entry/return boundary.
.macro save_hook_state
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
    je .LsaveFx\@
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
    jmp .Lsaved\@
.LsaveFx\@:
    fxsave64 (%rdi)
.Lsaved\@:
.endm

.macro restore_hook_state
    movq -96(%rbp),%rdi
    cmpl $0,fm_xsave_mode(%rip)
    je .LrestoreFx\@
    movl fm_xsave_low(%rip),%eax
    movl fm_xsave_high(%rip),%edx
    xrstor64 (%rdi)
    jmp .Lregisters\@
.LrestoreFx\@:
    fxrstor64 (%rdi)
.Lregisters\@:
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
.endm
