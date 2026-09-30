#pragma once
#include <stdint.h>

typedef struct FmLinuxTargeterLayout {
    uint64_t release;
    uint32_t targeterExtent;
    uint32_t targetableExtent;
    uint32_t target;
    uint32_t previous;
    uint32_t next;
    uint32_t head;
} FmLinuxTargeterLayout;

#ifdef __cplusplus
#include <array>

// These borrowed pointers are valid only within the same GUI safe-point operation.
struct TargeterLinks {
    uintptr_t target = 0;
    uintptr_t previous = 0;
    uintptr_t next = 0;
    uintptr_t first = 0;
};

bool validTargeterLayout(const FmLinuxTargeterLayout &layout);
int readTargeter(uintptr_t address, const FmLinuxTargeterLayout &layout, TargeterLinks &output);
int releaseTargeter(uintptr_t address, const FmLinuxTargeterLayout &layout);
// Requires the additional fresh non-null assignment proof. Storage must be zeroed, stable, and retained until
// successful release, including after an uncertain call. The caller supplies a freshly validated live targetable.
int attachFreshTargeter(uintptr_t address, uintptr_t target, const FmLinuxTargeterLayout &layout);

// Owns stable native list storage, not the target's lifetime. The native targetable clears its registered records
// on destruction. Borrow the current target only inside a safe point, and still validate the current UI tree.
// The containing command must outlive failed release; destruction must never replace explicit safe-point cleanup.
class TargetReference {
public:
    TargetReference() = default;
    TargetReference(const TargetReference &) = delete;
    TargetReference &operator=(const TargetReference &) = delete;
    TargetReference(TargetReference &&) = delete;
    TargetReference &operator=(TargetReference &&) = delete;

    bool owned() const { return owned_; }
    int attach(uintptr_t target, const FmLinuxTargeterLayout &layout);
    int borrow(uintptr_t &target) const;
    int release();

private:
    alignas(uintptr_t) std::array<unsigned char, 256> storage_{};
    FmLinuxTargeterLayout layout_{};
    bool owned_ = false;
};
#endif
