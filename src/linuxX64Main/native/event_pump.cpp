#include "event_pump.h"
#include <cerrno>
#include <sys/syscall.h>
#include <unistd.h>

int EventPump::dispatch(const FmLinuxPollHookConfig &site, Pump pump, void *pumpContext, Writer writer, void *payload,
                        EventPumpProgress &progress, pid_t thread) {
    progress = {};
    const pid_t expectedThread = thread ? thread : getpid();
    if (expectedThread <= 0 || syscall(SYS_gettid) != expectedThread)
        return EPERM;
    if (!validPollSite(site) || !pump || !writer)
        return EINVAL;
    if (busy_.test_and_set(std::memory_order_acquire))
        return EBUSY;
    site_ = site;
    writer_ = writer;
    payload_ = payload;
    progress_ = {};
    failure_ = 0;
    activeThread_.store(expectedThread, std::memory_order_release);
    try {
        pump(pumpContext);
        progress_.returned = true;
    } catch (...) {
        if (!failure_)
            failure_ = EFAULT;
    }
    activeThread_.store(0, std::memory_order_release);
    writer_ = nullptr;
    payload_ = nullptr;
    progress = progress_;
    if (!failure_ && (!progress_.delivered || !progress_.emptyObserved))
        failure_ = EPROTO;
    const int result = failure_;
    busy_.clear(std::memory_order_release);
    return result;
}

int EventPump::intercept(void *receiver, void *event, uintptr_t caller, uintptr_t stack) noexcept {
    // Only the thread synchronously executing pump() can inspect its payload. The atomic identity keeps
    // unrelated hook callbacks away from the non-atomic scope, including while a new dispatch is published.
    if (syscall(SYS_gettid) != activeThread_.load(std::memory_order_acquire))
        return -1;
    if (!matchesPollSite(site_, receiver, event, caller, stack)) {
        if (!failure_)
            failure_ = EPROTO;
        return 0;
    }
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
