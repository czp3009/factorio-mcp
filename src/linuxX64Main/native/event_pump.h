#pragma once
#include "poll_site.h"
#include <cstddef>

struct EventPumpProgress {
    bool delivered = false;
    bool emptyObserved = false;
    bool returned = false;
};

// One synchronous event routed by the native pump. This does not establish the pump's ABI or safe call phase.
// The verified caller supplies that boundary. The writer must validate before mutation, write only a proven
// non-owning event case, and never throw. A delivered event must never be replayed, including on pump failure.
class EventPump {
public:
    using Pump = void (*)(void *);
    using Writer = int (*)(void *, size_t, void *) noexcept;

    int dispatch(const FmLinuxPollHookConfig &site, Pump pump, void *pumpContext,
                 Writer writer, void *payload, EventPumpProgress &progress);
    // Before the actual native poll: -1 forwards, 0 supplies empty, 1 supplies the one event.
    // Other threads and unverified callers cannot consume this dispatch.
    int intercept(void *receiver, void *event, uintptr_t caller, uintptr_t frame) noexcept;

private:
    bool running_ = false;
    int failure_ = 0;
    FmLinuxPollHookConfig site_{};
    Writer writer_ = nullptr;
    void *payload_ = nullptr;
    EventPumpProgress progress_{};
};
