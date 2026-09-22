.section .text.traps,"ax",@progbits
.global payload_trap
.global payload_trap_end
.global remote_syscall
.global remote_syscall_end
payload_trap:
    int3
payload_trap_end:
remote_syscall:
    syscall
    int3
remote_syscall_end:
.section .note.GNU-stack,"",@progbits
