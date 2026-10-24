#pragma once
#include <stdint.h>

typedef struct FmLinuxFrameHookSite {
    uint64_t slot;
    uint64_t original;
    uint32_t protection;
} FmLinuxFrameHookSite;

#ifdef __cplusplus
#include "pointer_hook.h"

// Owns the module's API slot. Install/removal require the frontend main thread.
class FrameHookOwner {
public:
    FrameHookOwner(PointerHook::Protect protect, uint64_t &forward);
    int install(const FmLinuxFrameHookSite &site, uintptr_t replacement, size_t pageSize);
    int remove();
    bool ownsPointer() const;
    bool ownsProtection() const;
    bool hasOwnership() const;

private:
    PointerHook hook;
    uint64_t &forward;
    FmLinuxFrameHookSite bound{};
    uintptr_t replacement = 0;
};
#endif
