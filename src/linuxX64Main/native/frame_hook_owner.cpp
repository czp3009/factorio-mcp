#include "frame_hook_owner.h"
#include "memory_read.h"
#include <cerrno>
#include <sys/mman.h>
#include <sys/syscall.h>

namespace {
bool valid(const FmLinuxFrameHookSite &site) {
    return fm::addressRange(site.slot, sizeof(uintptr_t)) && site.slot % alignof(uintptr_t) == 0 &&
        fm::addressRange(site.original, 1) &&
        (site.protection == PROT_READ || site.protection == (PROT_READ | PROT_WRITE));
}

bool same(const FmLinuxFrameHookSite &a, const FmLinuxFrameHookSite &b) {
    return a.slot == b.slot && a.original == b.original && a.protection == b.protection;
}
} // namespace

FrameHookOwner::FrameHookOwner(PointerHook::Protect protect, uint64_t &forward) : hook(protect), forward(forward) {}

bool FrameHookOwner::ownsPointer() const { return hook.ownsPointer(); }

bool FrameHookOwner::ownsProtection() const { return hook.ownsProtection(); }

bool FrameHookOwner::hasOwnership() const { return hook.hasOwnership(); }

int FrameHookOwner::install(const FmLinuxFrameHookSite &site, uintptr_t next, size_t pageSize) {
    if (syscall(SYS_gettid) != getpid())
        return EPERM;
    if (!valid(site) || !fm::addressRange(next, 1) || next == site.original ||
        pageSize < sizeof(uintptr_t) || (pageSize & (pageSize - 1)))
        return EINVAL;
    if (hasOwnership()) {
        if (!same(site, bound) || replacement != next || ownsProtection())
            return EBUSY;
        uintptr_t value;
        if (!fm::read(bound.slot, value))
            return EFAULT;
        return ownsPointer() && value == replacement ? 0 : ESTALE;
    }
    // A wrapper already entered before removal must still tail-transfer to its own original function.
    if (forward && forward != site.original)
        return ESTALE;
    bound = site;
    uintptr_t value;
    if (!fm::read(site.slot, value))
        return EFAULT;
    if (value != site.original)
        return ESTALE;
    replacement = next;
    forward = site.original;
    return hook.install(site.slot, site.original, next, pageSize, site.protection);
}

int FrameHookOwner::remove() {
    if (syscall(SYS_gettid) != getpid())
        return EPERM;
    if (!hasOwnership())
        return 0;
    uintptr_t value;
    if (!fm::read(bound.slot, value))
        return EFAULT;
    if (value != (ownsPointer() ? replacement : bound.original))
        return ESTALE;
    return hook.remove();
}
