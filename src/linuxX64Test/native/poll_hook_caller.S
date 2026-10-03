// A native caller with no RBP frame chain. Its deliberately unrelated RBP must survive interception.
.text
.globl fm_fixture_poll_frameless
.hidden fm_fixture_poll_frameless
.type fm_fixture_poll_frameless, @function
fm_fixture_poll_frameless:
.cfi_startproc
pushq %rbp
.cfi_def_cfa_offset 16
.cfi_offset %rbp, -16
movq $0x1234, %rbp
movq %rsp, fm_fixture_poll_stack(%rip)
pcmpeqb %xmm14, %xmm14
movq (%rdi), %rax
call *(%rax)
cmpq $0x1234, %rbp
jne .Lbad_poll_frame
popq %rbp
.cfi_def_cfa_offset 8
.cfi_restore %rbp
ret
.Lbad_poll_frame:
ud2
.cfi_endproc
.size fm_fixture_poll_frameless, .-fm_fixture_poll_frameless
.section .note.GNU-stack, "", @progbits
