.text
.global resident_fixture
.type resident_fixture,@function
resident_fixture:
    movapd %xmm0, %xmm2
    addsd resident_fixture_half(%rip), %xmm2
    addsd %xmm1, %xmm2
    incq (%rdi)
    movapd %xmm2, %xmm0
    subsd resident_fixture_half(%rip), %xmm1
    ret
.global resident_fixture_end
resident_fixture_end:
.size resident_fixture,.-resident_fixture

.global resident_fixture_branch
.type resident_fixture_branch,@function
resident_fixture_branch:
    jmp resident_fixture
    .rept 20
    nop
    .endr
.global resident_fixture_branch_end
resident_fixture_branch_end:
.size resident_fixture_branch,.-resident_fixture_branch

.global resident_integer_fixture
.type resident_integer_fixture,@function
resident_integer_fixture:
    mov %rdi, %rax
    add %rsi, %rax
    .rept 16
    nop
    .endr
    ret
.global resident_integer_fixture_end
resident_integer_fixture_end:
.size resident_integer_fixture,.-resident_integer_fixture

.section .rodata
.balign 8
resident_fixture_half:
    .double 0.5
.section .note.GNU-stack,"",@progbits
