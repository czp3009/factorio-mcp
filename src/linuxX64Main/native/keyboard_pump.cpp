#include "keyboard_pump.h"
#include <cerrno>
#include <limits>
#include <sys/syscall.h>
#include <unistd.h>

KeyboardPumpKey::KeyboardPumpKey(EventPump &pump, const KeyboardPumpConfig &config,
                                 EventPump::Pump entry, void *context)
    : pump_(pump), config_(config), entry_(entry), context_(context) {}

int KeyboardPumpKey::record(int error) {
    if (error && !failure_)
        failure_ = error;
    return failure_;
}

int KeyboardPumpKey::current(KeyStateValue &value) {
    InputStateObjects objects;
    // Inspect identity before the key map: a replacement's map may not be initialized yet.
    if (const int error = readInputState(config_.owner, objects))
        return error;
    if (objects.global != global_ || objects.state != state_)
        return ESTALE;
    return readKeyState(config_.owner, config_.keys, static_cast<int32_t>(code_), objects, value);
}

int KeyboardPumpKey::write(void *event, size_t capacity, void *context) noexcept {
    auto &self = *static_cast<KeyboardPumpKey *>(context);
    KeyStateValue value;
    if (const int error = self.current(value))
        return error;
    if (self.writingDown_ && (value.held || value.blocked))
        return EBUSY;
    double time;
    if (const int error = readEventClock(self.config_.clock, time))
        return error;
    // A clock callback is still a native call; reacquire ownership before touching the empty Event.
    if (const int error = self.current(value))
        return error;
    if (self.writingDown_ && (value.held || value.blocked))
        return EBUSY;
    return writeKeyboardEvent(self.config_.event, event, capacity, self.code_, self.writingDown_, time);
}

int KeyboardPumpKey::press(uint32_t code) {
    if (syscall(SYS_gettid) != getpid())
        return EPERM;
    if (started_)
        return EALREADY;
    started_ = true;
    if (!entry_ || !validPollSite(config_.site) || !validKeyStateLayout(config_.owner, config_.keys) ||
        !validKeyboardEventLayout(config_.event) || config_.site.eventExtent != config_.event.extent ||
        config_.owner.eventSize != config_.event.extent || config_.owner.eventType != config_.event.type ||
        config_.owner.eventTime != config_.event.time || !code ||
        code > static_cast<uint32_t>(std::numeric_limits<int32_t>::max()))
        return record(EINVAL);
    InputStateObjects objects;
    KeyStateValue value;
    if (const int error = readKeyState(config_.owner, config_.keys, static_cast<int32_t>(code), objects, value))
        return record(error);
    if (value.held || value.blocked)
        return record(EBUSY);
    code_ = code;
    global_ = objects.global;
    state_ = objects.state;
    writingDown_ = true;
    dispatching_ = true;
    record(pump_.dispatch(config_.site, entry_, context_, write, this, press_));
    dispatching_ = false;
    // Delivery can precede a routing exception. Never lose the corresponding release obligation.
    owned_ = press_.delivered;
    return failure_;
}

int KeyboardPumpKey::release() {
    if (syscall(SYS_gettid) != getpid())
        return EPERM;
    if (dispatching_)
        return EBUSY;
    if (!started_)
        return EINVAL;
    if (!owned_)
        return failure_;
    KeyStateValue value;
    int error = current(value);
    // A replacement service says nothing about events already routed into a live game input source.
    // Keep ownership and never send the old up to the replacement.
    if (error)
        return record(error);
    if (!release_.delivered) {
        writingDown_ = false;
        dispatching_ = true;
        record(pump_.dispatch(config_.site, entry_, context_, write, this, release_));
        dispatching_ = false;
        // Rejection before delivery may be retried by a later cleanup phase, never by this call.
        if (!release_.delivered)
            return failure_;
        error = current(value);
        if (error)
            return record(error);
    }
    // An uncertain up is never replayed. A cleared InputState bit alone cannot establish that routing and
    // post-update finished; retain ownership if the native pump unwound or returned before its empty poll.
    if (release_.returned && release_.emptyObserved && !value.held)
        owned_ = false;
    else
        record(EPROTO);
    return failure_;
}
