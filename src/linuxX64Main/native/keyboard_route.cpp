#include "keyboard_route.h"
#include <cerrno>
#include <limits>
#include <sys/syscall.h>
#include <unistd.h>

KeyboardRouteKey::KeyboardRouteKey(const KeyboardRouteConfig &config, const EventRouteFunctions &functions,
                                 pid_t inputThread, pid_t cleanupThread, EventRoute::Resolve pressResolver,
                                 EventRoute::Resolve releaseResolver, void *context)
    : config_(config), functions_(functions), inputThread_(inputThread), cleanupThread_(cleanupThread),
      pressResolver_(pressResolver), releaseResolver_(releaseResolver), context_(context) {}

int KeyboardRouteKey::record(int error) {
    if (error && !failure_)
        failure_ = error;
    return failure_;
}

int KeyboardRouteKey::service() {
    InputStateObjects objects;
    if (const int error = readInputState(config_.owner, objects))
        return error;
    if (objects.global != global_ || objects.state != state_)
        return ESTALE;
    return 0;
}

int KeyboardRouteKey::current(KeyStateValue &value) {
    if (const int error = service())
        return error;
    InputStateObjects objects;
    return readKeyState(config_.owner, config_.keys, static_cast<int32_t>(code_), objects, value);
}

int KeyboardRouteKey::resolve(EventRouteStage stage, void *context, void *&receiver) noexcept {
    auto &self = *static_cast<KeyboardRouteKey *>(context);
    receiver = nullptr;
    if (const int error = self.service())
        return error;
    const auto resolver = self.down_ ? self.pressResolver_ : self.releaseResolver_;
    const int error = resolver(stage, self.context_, receiver);
    if (error) {
        receiver = nullptr;
        return error;
    }
    // A resolver must not replace the admitted process-wide service while selecting a receiver.
    if (const int changed = self.service()) {
        receiver = nullptr;
        return changed;
    }
    return 0;
}

int KeyboardRouteKey::dispatch(bool down, EventRouteProgress &progress) {
    dispatching_ = true;
    struct Reset {
        bool &value;
        ~Reset() { value = false; }
    } reset{dispatching_};
    down_ = down;
    KeyStateValue value;
    if (const int error = current(value))
        return error;
    if (down && (value.held || value.blocked))
        return EBUSY;
    double timestamp;
    if (const int error = readEventClock(config_.clock, timestamp))
        return error;
    if (const int error = current(value))
        return error;
    if (down && (value.held || value.blocked))
        return EBUSY;
    KeyboardEventStorage event;
    if (const int error = event.prepare(config_.event, code_, down, timestamp))
        return error;
    EventRoute route(static_cast<pid_t>(syscall(SYS_gettid)), functions_,
                     down ? config_.pressOrder : config_.releaseOrder, resolve, this);
    return route.dispatch(event.data(), progress);
}

int KeyboardRouteKey::press(uint32_t code) {
    if (inputThread_ <= 0 || syscall(SYS_gettid) != inputThread_)
        return EPERM;
    if (started_)
        return EALREADY;
    started_ = true;
    if (cleanupThread_ <= 0 || !pressResolver_ || !releaseResolver_ || !functions_.source ||
        !functions_.guiEvent || !functions_.guiLogic || !functions_.evaluate || !functions_.update ||
        !functions_.postUpdate || !validKeyStateLayout(config_.owner, config_.keys) ||
        !validKeyboardEventLayout(config_.event) || config_.owner.eventSize != config_.event.extent ||
        config_.owner.eventType != config_.event.type || config_.owner.eventTime != config_.event.time ||
        (config_.pressOrder != EventUpdateOrder::BeforeSource && config_.pressOrder != EventUpdateOrder::AfterEvaluation) ||
        (config_.releaseOrder != EventUpdateOrder::BeforeSource && config_.releaseOrder != EventUpdateOrder::AfterEvaluation) ||
        !code || code > static_cast<uint32_t>(std::numeric_limits<int32_t>::max()))
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
    record(dispatch(true, press_));
    // State update or source dispatch may mutate before throwing; neither loses the up obligation.
    owned_ = press_.entered != 0;
    return failure_;
}

int KeyboardRouteKey::release() {
    const auto thread = syscall(SYS_gettid);
    if (thread != inputThread_ && thread != cleanupThread_)
        return EPERM;
    if (dispatching_)
        return EBUSY;
    if (!started_)
        return EINVAL;
    if (!owned_)
        return failure_;
    KeyStateValue value;
    if (const int error = current(value))
        return record(error);
    if (!release_.entered) {
        const int error = dispatch(false, release_);
        record(error);
        if (error)
            return failure_;
    }
    // A partial or uncertain route is never replayed. All entered calls must return, and the final
    // post-update must complete, before a passive cleared-key observation can discharge ownership.
    const auto post = static_cast<uint8_t>(EventRouteStage::PostUpdate);
    if (!(release_.returned & post) || release_.entered != release_.returned)
        return record(EPROTO);
    if (const int error = current(value))
        return record(error);
    if (value.held)
        return record(EPROTO);
    owned_ = false;
    return failure_;
}

KeyboardRouteKeys::KeyboardRouteKeys(const KeyboardRouteConfig &config, const EventRouteFunctions &functions,
                                   pid_t inputThread, pid_t cleanupThread, EventRoute::Resolve pressResolver,
                                   EventRoute::Resolve releaseResolver, void *context)
    : config_(config), functions_(functions), inputThread_(inputThread), cleanupThread_(cleanupThread),
      pressResolver_(pressResolver), releaseResolver_(releaseResolver), context_(context) {}

int KeyboardRouteKeys::button(uint32_t code, bool down) {
    const auto thread = syscall(SYS_gettid);
    if (inputThread_ <= 0 || (thread != inputThread_ && (down || thread != cleanupThread_)))
        return EPERM;
    if (dispatching_)
        return EBUSY;
    if (!code || code > static_cast<uint32_t>(std::numeric_limits<int32_t>::max()))
        return EINVAL;
    Slot *selected = nullptr;
    Slot *empty = nullptr;
    for (auto &slot : slots_) {
        if (slot.key && slot.code == code)
            selected = &slot;
        if (!slot.key && !empty)
            empty = &slot;
    }
    if (down && selected)
        return EALREADY;
    // InputSequence records an up obligation before invoking down. A rejected down may never acquire a key.
    if (!down && !selected)
        return 0;
    if (down) {
        if (!empty)
            return ENOSPC;
        selected = empty;
        selected->code = code;
        selected->key.emplace(config_, functions_, inputThread_, cleanupThread_, pressResolver_, releaseResolver_, context_);
    }
    dispatching_ = true;
    struct Reset {
        bool &value;
        ~Reset() { value = false; }
    } reset{dispatching_};
    const int error = down ? selected->key->press(code) : selected->key->release();
    if (!selected->key->owned()) {
        selected->key.reset();
        selected->code = 0;
        return down ? error : 0;
    }
    return down ? error : (error ? error : EBUSY);
}

bool KeyboardRouteKeys::owned() const {
    for (const auto &slot : slots_)
        if (slot.key && slot.key->owned())
            return true;
    return false;
}
