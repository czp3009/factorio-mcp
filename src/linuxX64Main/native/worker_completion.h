#pragma once
#include <stdint.h>

typedef struct FmLinuxWorkerCompletionLayout {
    uint64_t listenerVtable;
    uint64_t listenerTypeInfo;
    uint64_t caller;
    uint32_t workerSize;
    uint32_t listenerMember;
} FmLinuxWorkerCompletionLayout;

typedef struct FmLinuxWorkerCompletionConfig {
    FmLinuxWorkerCompletionLayout layout;
    uint64_t entry;
    uint64_t original;
    uint32_t protection;
} FmLinuxWorkerCompletionConfig;

#ifdef __cplusplus
#include <atomic>
#include <sys/types.h>

using WorkerCompletionLayout = FmLinuxWorkerCompletionLayout;

struct WorkerIdentity {
    pid_t thread = 0;
    uintptr_t worker = 0;
    uintptr_t listener = 0;
};

// Observes the unchanged native completion call, without invoking game code or accessing GUI state. Binding
// establishes only worker ownership. The task owner must separately authorize its execution phase and retain
// this object until all observer callbacks have returned. Stored object addresses are comparison tokens only.
class WorkerCompletion {
public:
    explicit WorkerCompletion(const WorkerCompletionLayout &layout);
    int observe(uintptr_t listener, uintptr_t worker, uintptr_t caller) noexcept;
    int identity(WorkerIdentity &output) noexcept;

private:
    const WorkerCompletionLayout layout_;
    const bool valid_;
    std::atomic_flag busy_ = ATOMIC_FLAG_INIT;
    WorkerIdentity identity_;
    bool invalidated_ = false;
};

extern "C" {
extern uint64_t fm_worker_completion_original;
void fm_worker_completion_hook();
void fm_before_worker_completion(void *listener, void *worker, uintptr_t caller) noexcept;
}
#endif
