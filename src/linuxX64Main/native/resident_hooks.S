.text
.hidden resident_xsave_mask
.hidden resident_before
.hidden resident_after
.hidden resident_enter
.hidden resident_leave

.macro dispatch function
    pushfq
    push %rax
    push %rcx
    push %rdx
    push %rbx
    push %rbp
    push %rsi
    push %rdi
    push %r8
    push %r9
    push %r10
    push %r11
    push %r12
    push %r13
    push %r14
    push %r15
    mov %rsp, %r12
    sub $65600, %rsp
    and $-64, %rsp
    mov %rsp, %r13
    mov %rsp, %rdi
    xor %eax, %eax
    mov $8192, %ecx
    cld
    rep stosq
    mov resident_xsave_mask(%rip), %rax
    test %rax, %rax
    jz 1f
    mov %rax, %rdx
    shr $32, %rdx
    xsave64 (%r13)
    jmp 2f
1:  fxsave64 (%r13)
2:  mov %r12, %rdi
    mov %r13, %rsi
    call \function
    mov resident_xsave_mask(%rip), %rax
    test %rax, %rax
    jz 3f
    mov %rax, %rdx
    shr $32, %rdx
    xrstor64 (%r13)
    jmp 4f
3:  fxrstor64 (%r13)
4:  mov %r12, %rsp
    pop %r15
    pop %r14
    pop %r13
    pop %r12
    pop %r11
    pop %r10
    pop %r9
    pop %r8
    pop %rdi
    pop %rsi
    pop %rbp
    pop %rbx
    pop %rdx
    pop %rcx
    pop %rax
    popfq
    ret
.endm

.global resident_before
.type resident_before,@function
resident_before:
    dispatch resident_enter
.size resident_before,.-resident_before

.global resident_after
.type resident_after,@function
resident_after:
    pushq $0
    dispatch resident_leave
.size resident_after,.-resident_after
.section .note.GNU-stack,"",@progbits
