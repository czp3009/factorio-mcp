#include "input_evaluation.h"
#include <limits>
#include <stdexcept>
#include <sys/syscall.h>
#include <unistd.h>

namespace {
bool mainThread() {
    return syscall(SYS_gettid) == getpid();
}

struct Lease {
    std::atomic_flag &gate;
    bool retained = false;
    explicit Lease(std::atomic_flag &value) : gate(value) {}
    ~Lease() {
        if (!retained)
            gate.clear(std::memory_order_release);
    }
};
} // namespace

InputEvaluation::InputEvaluation(InputTaskContext &context, InputSequence &sequence, InputEmitter &native,
                                 pid_t evaluationThread, uintptr_t evaluationCaller)
    : context_(context), sequence_(sequence), emitter_(context, native), evaluationThread_(evaluationThread),
      evaluationCaller_(evaluationCaller) {
    if (evaluationThread <= 0 || !evaluationCaller || evaluationCaller > INTPTR_MAX)
        throw std::invalid_argument("Input evaluation requires an established thread and caller");
}

bool InputEvaluation::finished() const {
    if (busy_.test_and_set(std::memory_order_acquire))
        return false;
    Lease guard(busy_);
    const auto state = sequence_.state();
    return !pending() && !sequence_.hasHeldInput() &&
        (state == InputSequenceState::Succeeded || state == InputSequenceState::Aborted);
}

void InputEvaluation::observeCancellation() {
    if (cancelRequested_.load(std::memory_order_acquire))
        sequence_.cancel("Input task canceled");
}

uint64_t InputEvaluation::before(uintptr_t receiver, uintptr_t caller) {
    if (caller != evaluationCaller_ || syscall(SYS_gettid) != evaluationThread_ ||
        busy_.test_and_set(std::memory_order_acquire))
        return 0;
    Lease guard(busy_);
    observeCancellation();
    if (sequence_.state() != InputSequenceState::Running)
        return 0;
    InputContext current;
    if (context_.read(current)) {
        sequence_.cancel("Input context is unavailable before evaluation");
        return 0;
    }
    if (receiver != current.source)
        return 0;
    if (current.paused || current.stopped) {
        sequence_.cancel("Input world is not running");
        return 0;
    }
    if (nextCookie_ == std::numeric_limits<uint64_t>::max()) {
        sequence_.cancel("Input evaluation identity exhausted");
        return 0;
    }
    pending_ = {nextCookie_++, current.tick, receiver};
    sequence_.beforeTick(current.tick, emitter_);
    // Dispatch and even cleanup callbacks can unload or replace the receiver. Never invoke the saved receiver
    // after such a change. A failure before dispatch above instead forwards the game's unchanged call.
    if (context_.read(current) || current.source != receiver || current.tick != pending_.tick ||
        current.paused || current.stopped) {
        pending_ = {};
        sequence_.cancel("Input context changed during preparation");
        return 1;
    }
    pendingPublished_.store(true, std::memory_order_release);
    guard.retained = true;
    return pending_.cookie;
}

void InputEvaluation::after(uintptr_t receiver, uint64_t cookie) {
    if (syscall(SYS_gettid) != evaluationThread_ || !pending())
        return;
    if (cookie != pending_.cookie || receiver != pending_.receiver) {
        sequence_.cancel("Input evaluation completion did not match its admission");
        return;
    }
    pendingPublished_.store(false, std::memory_order_release);
    Lease guard(busy_);
    const auto tick = pending_.tick;
    pending_ = {};
    InputContext current;
    if (context_.read(current) || current.source != receiver || current.tick != tick ||
        current.paused || current.stopped) {
        sequence_.cancel("Input context changed during native evaluation");
        return;
    }
    sequence_.afterTick(tick);
    // Account for the evaluation that actually returned before honoring a request made during that call.
    observeCancellation();
}

void InputEvaluation::aborted(uintptr_t receiver, uint64_t cookie) {
    if (syscall(SYS_gettid) != evaluationThread_ || !pending())
        return;
    sequence_.cancel("Native input evaluation unwound");
    if (cookie != pending_.cookie || receiver != pending_.receiver)
        return;
    pendingPublished_.store(false, std::memory_order_release);
    Lease guard(busy_);
    pending_ = {};
    // Do not dispatch input while the game's native exception cleanup is still in progress.
}

void InputEvaluation::frontend() {
    if (!mainThread() || busy_.test_and_set(std::memory_order_acquire))
        return;
    Lease guard(busy_);
    observeCancellation();
    if (sequence_.state() == InputSequenceState::Running) {
        InputContext current;
        if (context_.read(current))
            sequence_.cancel("Input context is unavailable at the frontend");
        else if (current.paused || current.stopped)
            sequence_.cancel("Input world stopped before task completion");
    }
    // Context binding remains owned until the caller observes finished() and releases it.
    // A concrete emitter must reconcile a previously entered up rather than replaying uncertain native input.
    sequence_.cleanup(emitter_);
}
