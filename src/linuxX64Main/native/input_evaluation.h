#pragma once
#include "input_task_context.h"
#include <atomic>
#include <sys/types.h>

// One admitted task. The owner establishes the evaluating thread and eligible frontend/game phases; a TID
// alone is not phase authorization. The nonblocking gate spans the original native evaluation, including its
// exception cleanup notification. The owner must keep this object alive and prevent new admissions before
// releasing its context after finished(). No queue or game object belongs to this adapter.
class InputEvaluation {
public:
    InputEvaluation(InputTaskContext &context, InputSequence &sequence, InputEmitter &native,
                    pid_t evaluationThread, uintptr_t evaluationCaller);
    // Matches evaluation_hook.h: 0 forwards without accounting, 1 skips an invalidated receiver, >1 pairs a return.
    uint64_t before(uintptr_t receiver, uintptr_t caller);
    void after(uintptr_t receiver, uint64_t cookie);
    void aborted(uintptr_t receiver, uint64_t cookie);
    // Publication only, safe from the IPC owner while native evaluation is in flight. Sequence mutation and
    // release dispatch remain in the admitted evaluation/frontend phases, never on the requesting thread.
    void cancel() noexcept { cancelRequested_.store(true, std::memory_order_release); }
    void frontend();
    bool pending() const { return pendingPublished_.load(std::memory_order_acquire); }
    // The task/context must remain alive until this is true, even if sequence cleanup finished in before().
    bool finished() const;

private:
    struct Pending {
        uint64_t cookie = 0;
        uint64_t tick = 0;
        uintptr_t receiver = 0;
    } pending_;
    InputTaskContext &context_;
    InputSequence &sequence_;
    GuardedInputEmitter emitter_;
    const pid_t evaluationThread_;
    const uintptr_t evaluationCaller_;
    mutable std::atomic_flag busy_ = ATOMIC_FLAG_INIT;
    std::atomic<bool> pendingPublished_{false};
    std::atomic<bool> cancelRequested_{false};
    uint64_t nextCookie_ = 2;
    void observeCancellation();
};
