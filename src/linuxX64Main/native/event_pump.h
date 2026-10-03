#pragma once
#include "poll_site.h"
#include <atomic>
#include <cstddef>
#include <sys/types.h>

struct EventPumpProgress {
    bool delivered = false;
    bool emptyObserved = false;
    bool returned = false;
};

// One synchronous event routed by the native pump. This does not establish the pump's ABI or safe call phase.
// The verified caller supplies that boundary and, for worker dispatch, the observed evaluation thread.
// Thread identity alone does not establish a safe phase. The writer must validate before mutation, write only a proven
// non-owning event case, and never throw. A delivered event must never be replayed, including on pump failure.
class EventPump {
  public:
    using Pump = void (*)(void *);
    using Writer = int (*)(void *, size_t, void *) noexcept;

    int dispatch(const FmLinuxPollHookConfig &site, Pump pump, void *pumpContext, Writer writer, void *payload,
                 EventPumpProgress &progress, pid_t thread = 0);
    // Before the actual native poll: -1 forwards, 0 supplies empty, 1 supplies the one event.
    // Other threads cannot consume this dispatch. An unexpected caller on the dispatch thread supplies
    // empty and fails the scope, rather than entering the actual platform event poll on that thread.
    int intercept(void *receiver, void *event, uintptr_t caller, uintptr_t stack) noexcept;

  private:
    std::atomic_flag busy_ = ATOMIC_FLAG_INIT;
    std::atomic<pid_t> activeThread_{0};
    int failure_ = 0;
    FmLinuxPollHookConfig site_{};
    Writer writer_ = nullptr;
    void *payload_ = nullptr;
    EventPumpProgress progress_{};
};
