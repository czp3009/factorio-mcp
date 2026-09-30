.text
.globl fm_fixture_frame_invoke
.globl fm_fixture_frame_return
.globl fm_fixture_frame_original
.type fm_fixture_frame_invoke,@function
fm_fixture_frame_invoke:
    .cfi_startproc
    pushq %rbp
    .cfi_def_cfa_offset 16
    .cfi_offset %rbp,-16
    movq %rsp,%rbp
    .cfi_def_cfa_register %rbp
    subq $16,%rsp
    movq %rdx,%r11
    movq $55,(%rsp)
    movq $66,8(%rsp)
    movq $11,%rdx
    movq $22,%rcx
    movq $33,%r8
    movq $44,%r9
    movsd .LframeNumber(%rip),%xmm0
    stc
    call *%r11
fm_fixture_frame_return:
    leave
    .cfi_def_cfa %rsp,8
    .cfi_restore %rbp
    ret
    .cfi_endproc
.size fm_fixture_frame_invoke,.-fm_fixture_frame_invoke
.type fm_fixture_frame_original,@function
fm_fixture_frame_original:
    .cfi_startproc
    setc fm_fixture_frame_carry(%rip)
    jmp fm_fixture_frame_body
    .cfi_endproc
.size fm_fixture_frame_original,.-fm_fixture_frame_original
.section .rodata
.p2align 3
.LframeNumber:
    .double 3.25
.section .note.GNU-stack,"",@progbits
