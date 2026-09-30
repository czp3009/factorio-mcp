#pragma once
// Linux UAPI constants missing from the Kotlin/Native glibc sysroot.
#include <linux/fcntl.h>
#include <linux/memfd.h>
#include <sys/syscall.h>

enum {
    FM_MEMFD_SYSCALL = SYS_memfd_create,
    FM_MEMFD_FLAGS = MFD_CLOEXEC | MFD_ALLOW_SEALING,
    FM_ADD_SEALS = F_ADD_SEALS,
    FM_GET_SEALS = F_GET_SEALS,
    FM_SIZE_SEALS = F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_SEAL
};
