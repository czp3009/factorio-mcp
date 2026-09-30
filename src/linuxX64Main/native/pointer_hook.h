#pragma once
#include <cstddef>
#include <cstdint>

// Owns one aligned pointer and any temporarily changed page protection, including partial failures.
class PointerHook {
public:
    using Protect = int (*)(void *, size_t, int);

    explicit PointerHook(Protect protect);
    int install(uintptr_t entry, uintptr_t original, uintptr_t replacement, size_t pageSize, int protection);
    int remove();
    bool ownsPointer() const;
    bool ownsProtection() const;
    bool hasOwnership() const;

private:
    Protect protect;
    uintptr_t *entry = nullptr;
    uintptr_t original = 0;
    uintptr_t replacement = 0;
    void *page = nullptr;
    size_t pageSize = 0;
    int protection = 0;
    bool pointerOwned = false;
    bool protectionOwned = false;

    int makeWritable();
    int restoreProtection();
};
