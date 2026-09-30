#pragma once
#include <linux/ptrace.h>
#include <stddef.h>
#include <sys/user.h>
#include <sys/wait.h>

enum { FM_TRACE_EVENT_STOP = PTRACE_EVENT_STOP };
enum { FM_DEBUG_REGISTERS_OFFSET = offsetof(struct user, u_debugreg) };
enum { FM_DEBUG_REGISTER_WIDTH = sizeof(((struct user *)0)->u_debugreg[0]) };

// Function-like wait macros are absent from Kotlin/Native's platform bindings.
static inline int fm_wait_exited(int status) {
    return WIFEXITED(status);
}

static inline int fm_wait_signaled(int status) {
    return WIFSIGNALED(status);
}

static inline int fm_wait_stopped(int status) {
    return WIFSTOPPED(status);
}

static inline int fm_wait_stop_signal(int status) {
    return WSTOPSIG(status);
}

static inline int fm_wait_exit_code(int status) {
    return WEXITSTATUS(status);
}
