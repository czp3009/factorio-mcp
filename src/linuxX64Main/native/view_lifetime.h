#pragma once
#include <atomic>
#include <cstdint>

// Comparison-only identity. No retained address from this guard may be dereferenced. The owning task acquires
// its current GameView afresh at a verified safe point, with a generation read immediately before that lookup.
// Bind/release belong to the same serialized owner; retirement notifications can arrive on other threads.
class ViewLifetime {
public:
    uint64_t generation() const noexcept;
    int bind(uintptr_t view, uint64_t observedGeneration) noexcept;
    bool valid(uintptr_t currentView) const noexcept;
    bool owned() const noexcept;
    void retired(uintptr_t view) noexcept;
    void release() noexcept;

private:
    std::atomic<uint64_t> generation_{0};
    std::atomic<uintptr_t> view_{0};
    std::atomic<bool> invalid_{true};
    std::atomic<bool> exhausted_{false};
};

extern "C" {
extern uint64_t fm_view_retire_original;
void fm_view_retire_hook();
void fm_before_view_retire(void *receiver) noexcept;
}
