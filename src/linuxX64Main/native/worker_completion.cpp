#include "worker_completion.h"
#include "memory_read.h"
#include <cerrno>
#include <sys/syscall.h>
#include <unistd.h>

namespace {
bool valid(const WorkerCompletionLayout &layout) {
    return layout.workerSize >= 8 && layout.workerSize <= 65536 &&
        layout.listenerMember % 8 == 0 && fm::member(layout.listenerMember, 8, layout.workerSize) &&
        layout.listenerVtable >= 16 && layout.listenerVtable % 8 == 0 &&
        fm::addressRange(layout.listenerVtable - 16, 24) && layout.listenerTypeInfo % 8 == 0 &&
        fm::addressRange(layout.listenerTypeInfo, 16) && fm::addressRange(layout.caller, 1);
}

struct Lease {
    std::atomic_flag &gate;
    ~Lease() { gate.clear(std::memory_order_release); }
};
} // namespace

WorkerCompletion::WorkerCompletion(const WorkerCompletionLayout &layout) : layout_(layout), valid_(valid(layout)) {}

int WorkerCompletion::observe(uintptr_t listener, uintptr_t worker, uintptr_t caller) noexcept {
    if (!valid_)
        return EINVAL;
    const auto thread = static_cast<pid_t>(syscall(SYS_gettid));
    if (caller != layout_.caller || thread == getpid())
        return EPERM;
    if (busy_.test_and_set(std::memory_order_acquire))
        return EAGAIN;
    Lease lease{busy_};
    if (invalidated_)
        return ESTALE;
    if (listener % 8 || worker % 8 || !fm::addressRange(listener, 8) ||
        !fm::addressRange(worker, layout_.workerSize))
        return EINVAL;
    uintptr_t actual, table, adjustment, type;
    if (!fm::read(worker + layout_.listenerMember, actual) || !fm::read(listener, table))
        return EFAULT;
    if (actual != listener || table != layout_.listenerVtable)
        return ESTALE;
    if (!fm::read(table - 16, adjustment) || !fm::read(table - 8, type))
        return EFAULT;
    if (adjustment || type != layout_.listenerTypeInfo)
        return ESTALE;
    if (identity_.thread && (identity_.thread != thread || identity_.worker != worker || identity_.listener != listener)) {
        invalidated_ = true;
        return ESTALE;
    }
    identity_ = {thread, worker, listener};
    return 0;
}

int WorkerCompletion::identity(WorkerIdentity &output) noexcept {
    output = {};
    if (!valid_)
        return EINVAL;
    if (busy_.test_and_set(std::memory_order_acquire))
        return EAGAIN;
    Lease lease{busy_};
    if (invalidated_)
        return ESTALE;
    if (!identity_.thread)
        return ENOENT;
    output = identity_;
    return 0;
}
