#pragma once
#include "input_evaluation.h"
#include "input_task_mapping.h"
#include <atomic>
#include <optional>

// Owns one admitted task's native lifetime. Kotlin decides admission/replacement; this adapter never queues
// tasks or waits. The emitter and phase/entry validation must outlive owned(), including failed cleanup.
class InputTaskSession {
  public:
    explicit InputTaskSession(ViewLifetime &lifetime, InputTaskContext::Reader reader = readInputContext);
    int start(const FmLinuxInputContextConfig &config, pid_t owner, int descriptor, pid_t thread, uintptr_t caller,
              InputEmitter &emitter);

    bool owned() const {
        return owned_.load(std::memory_order_acquire);
    }

    void cancel() noexcept {
        canceled_.store(true, std::memory_order_release);
    }

    int frontend();
    uint64_t before(uintptr_t receiver, uintptr_t caller);
    void after(uintptr_t receiver, uint64_t cookie);
    void aborted(uintptr_t receiver, uint64_t cookie);
    // Called only synchronously by this task's emitter while the session owns its execution gate.
    int validateContext();
    int readContext(InputContext &output);

  private:
    struct Task {
        InputTaskMapping mapping;
        InputTaskContext context;
        std::optional<InputSequence> sequence;
        std::optional<InputEvaluation> evaluation;
        bool admitted = false;
        bool finalized = false;

        Task(ViewLifetime &lifetime, InputTaskContext::Reader reader) : context(lifetime, reader) {}
    };

    ViewLifetime &lifetime_;
    InputTaskContext::Reader reader_;
    std::optional<Task> task_;
    std::atomic_flag busy_ = ATOMIC_FLAG_INIT;
    std::atomic<bool> owned_{false}, canceled_{false};
    std::atomic<pid_t> thread_{0};
    std::atomic<uint64_t> cookie_{0};
    uintptr_t receiver_ = 0;
    void observeCancellation();
    void publish();
    int closeFinished();
};
