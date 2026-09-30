#include "event_pump.h"
#include <cerrno>
#include <sys/syscall.h>
#include <unistd.h>

int EventPump::dispatch(const FmLinuxPollHookConfig &site, Pump pump, void *pumpContext,
                        Writer writer, void *payload, EventPumpProgress &progress) {
    progress = {};
    if (syscall(SYS_gettid) != getpid())
        return EPERM;
    if (running_)
        return EBUSY;
    if (!validPollSite(site) || !pump || !writer)
        return EINVAL;
    site_ = site;
    writer_ = writer;
    payload_ = payload;
    progress_ = {};
    failure_ = 0;
    running_ = true;
    try {
        pump(pumpContext);
        progress_.returned = true;
    } catch (...) {
        if (!failure_)
            failure_ = EFAULT;
    }
    running_ = false;
    writer_ = nullptr;
    payload_ = nullptr;
    progress = progress_;
    if (!failure_ && (!progress_.delivered || !progress_.emptyObserved))
        failure_ = EPROTO;
    return failure_;
}

int EventPump::intercept(void *receiver, void *event, uintptr_t caller, uintptr_t frame) noexcept {
    // Every access to the synchronous dispatch fields is confined to the process's main thread.
    if (syscall(SYS_gettid) != getpid() || !running_ || !matchesPollSite(site_, receiver, event, caller, frame))
        return -1;
    if (failure_ || progress_.delivered) {
        progress_.emptyObserved = true;
        return 0;
    }
    failure_ = writer_(event, site_.eventExtent, payload_);
    if (failure_) {
        progress_.emptyObserved = true;
        return 0;
    }
    progress_.delivered = true;
    return 1;
}
