#include "pointer_hook.h"
#include <cerrno>
#include <sys/mman.h>

static_assert(__atomic_always_lock_free(sizeof(uintptr_t), nullptr));

PointerHook::PointerHook(Protect protect) : protect(protect) {}

bool PointerHook::ownsPointer() const {
    return pointerOwned;
}

bool PointerHook::ownsProtection() const {
    return protectionOwned;
}

bool PointerHook::hasOwnership() const {
    return pointerOwned || protectionOwned;
}

int PointerHook::makeWritable() {
    if (protectionOwned || (protection & PROT_WRITE))
        return 0;
    if (protect(page, pageSize, protection | PROT_WRITE) != 0)
        return errno;
    protectionOwned = true;
    return 0;
}

int PointerHook::restoreProtection() {
    if (!protectionOwned)
        return 0;
    if (protect(page, pageSize, protection) != 0)
        return errno;
    protectionOwned = false;
    return 0;
}

int PointerHook::install(uintptr_t location, uintptr_t expected, uintptr_t hook, size_t size, int flags) {
    if (hasOwnership())
        return EBUSY;
    if (!location || !expected || !hook || expected == hook || location % alignof(uintptr_t) != 0 ||
        size < sizeof(uintptr_t) || (size & (size - 1)) != 0 ||
        (flags != PROT_READ && flags != (PROT_READ | PROT_WRITE)))
        return EINVAL;
    entry = reinterpret_cast<uintptr_t *>(location);
    original = expected;
    replacement = hook;
    page = reinterpret_cast<void *>(location & ~(size - 1));
    pageSize = size;
    protection = flags;
    if (__atomic_load_n(entry, __ATOMIC_ACQUIRE) != original)
        return ESTALE;
    const int writable = makeWritable();
    if (writable)
        return writable;
    uintptr_t current = original;
    if (!__atomic_compare_exchange_n(entry, &current, replacement, false, __ATOMIC_RELEASE, __ATOMIC_RELAXED)) {
        const int cleanup = restoreProtection();
        return cleanup ? cleanup : ESTALE;
    }
    pointerOwned = true;
    return restoreProtection();
}

int PointerHook::remove() {
    if (pointerOwned) {
        if (__atomic_load_n(entry, __ATOMIC_ACQUIRE) != replacement)
            return ESTALE;
        const int writable = makeWritable();
        if (writable)
            return writable;
        uintptr_t current = replacement;
        if (!__atomic_compare_exchange_n(entry, &current, original, false, __ATOMIC_RELEASE, __ATOMIC_RELAXED)) {
            const int cleanup = restoreProtection();
            return cleanup ? cleanup : ESTALE;
        }
        pointerOwned = false;
    }
    return restoreProtection();
}
