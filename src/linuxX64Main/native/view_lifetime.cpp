#include "view_lifetime.h"
#include <cerrno>
#include <limits>

static_assert(std::atomic<uint64_t>::is_always_lock_free);
static_assert(std::atomic<uintptr_t>::is_always_lock_free);
static_assert(std::atomic<bool>::is_always_lock_free);

uint64_t ViewLifetime::generation() const noexcept {
    return generation_.load();
}

int ViewLifetime::bind(uintptr_t view, uint64_t observedGeneration) noexcept {
    if (!view || view % alignof(uintptr_t))
        return EINVAL;
    if (owned())
        return EBUSY;
    if (exhausted_.load())
        return EOVERFLOW;
    if (generation_.load() != observedGeneration)
        return ESTALE;
    invalid_.store(false);
    view_.store(view);
    // Notifications before publication are caught by the generation; those after it mark this identity invalid.
    // Retirements of unrelated views during acquisition also reject, rather than guessing which read raced.
    if (generation_.load() != observedGeneration || exhausted_.load() || invalid_.load()) {
        release();
        return ESTALE;
    }
    return 0;
}

bool ViewLifetime::valid(uintptr_t currentView) const noexcept {
    return currentView && view_.load() == currentView && !invalid_.load() && !exhausted_.load();
}

bool ViewLifetime::owned() const noexcept {
    return view_.load() != 0;
}

void ViewLifetime::retired(uintptr_t view) noexcept {
    // Advance before inspecting the bound identity so an acquisition cannot slip between the two observations.
    if (generation_.fetch_add(1) == std::numeric_limits<uint64_t>::max())
        exhausted_.store(true);
    if (view && view_.load() == view)
        invalid_.store(true);
}

void ViewLifetime::release() noexcept {
    view_.store(0);
    invalid_.store(true);
}
