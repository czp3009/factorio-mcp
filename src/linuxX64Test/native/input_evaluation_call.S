.text
.globl fm_fixture_evaluation_invoke
.globl fm_fixture_evaluation_return
.type fm_fixture_evaluation_invoke,@function
fm_fixture_evaluation_invoke:
    .cfi_startproc
    pushq %rbp
    .cfi_def_cfa_offset 16
    .cfi_offset %rbp,-16
    movq %rsp,%rbp
    .cfi_def_cfa_register %rbp
    call fm_evaluation_hook
fm_fixture_evaluation_return:
    leave
    .cfi_def_cfa %rsp,8
    .cfi_restore %rbp
    ret
    .cfi_endproc
.size fm_fixture_evaluation_invoke,.-fm_fixture_evaluation_invoke
.section .note.GNU-stack,"",@progbits
