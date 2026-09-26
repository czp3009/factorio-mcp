// A registered non-leaf frame with integer, vector and stack arguments.
.intel_syntax noprefix
.text
.globl fixture
.def fixture; .scl 2; .type 32; .endef
.seh_proc fixture
fixture:
    push rbx
    .seh_pushreg rbx
    sub rsp, 32
    .seh_stackalloc 32
    .seh_endprologue
    mov rbx, rcx
    .rept 6
    nop
    .endr
    lea rax, [rbx+r8]
    add rax, qword ptr [rsp+80]
    cvttsd2si rdx, xmm1
    add rax, rdx
    cvttsd2si rdx, xmm3
    add rax, rdx
    add rsp, 32
    pop rbx
    ret
.seh_endproc
