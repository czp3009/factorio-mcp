#include "pointer_pump.h"
#include <cerrno>
#include <sys/syscall.h>
#include <unistd.h>

namespace {
pid_t currentThread() {
    return static_cast<pid_t>(syscall(SYS_gettid));
}

struct Busy {
    bool &flag;

    explicit Busy(bool &value) : flag(value) {
        flag = true;
    }

    ~Busy() {
        flag = false;
    }
};
} // namespace

bool validPointerPumpConfig(const PointerPumpConfig &config) {
    if (!validPollSite(config.site) || !validPointerStateLayout(config.owner, config.state) ||
        !validPointerEventLayout(config.event) || config.site.eventExtent != config.event.extent ||
        config.owner.eventSize != config.event.extent || config.owner.eventType != config.event.type ||
        config.owner.eventTime != config.event.time ||
        config.owner.eventCode != config.event.cases[FM_LINUX_POINTER_PRESS].code ||
        config.owner.eventCode != config.event.cases[FM_LINUX_POINTER_RELEASE].code ||
        config.owner.press != config.event.cases[FM_LINUX_POINTER_PRESS].kind ||
        config.owner.release != config.event.cases[FM_LINUX_POINTER_RELEASE].kind)
        return false;
    constexpr unsigned order[]{0, 2, 1};
    for (unsigned index = 0; index < FM_LINUX_MOUSE_BUTTONS; ++index)
        if (config.owner.codes[index] != config.event.codes[order[index]] ||
            config.owner.masks[index] != config.state.masks[order[index]])
            return false;
    return true;
}

PointerPumpButton::PointerPumpButton(EventPump &pump, const PointerPumpConfig &config, EventPump::Pump entry,
                                     void *context, pid_t evaluationThread)
    : pump_(pump), config_(config), entry_(entry), context_(context), evaluationThread_(evaluationThread) {}

int PointerPumpButton::record(int error) {
    if (error && !failure_)
        failure_ = error;
    return failure_;
}

int PointerPumpButton::current(InputStateObjects &objects, PointerStateValue &value) {
    if (const int error = readInputState(config_.owner, objects))
        return error;
    if (objects.global != global_ || objects.state != state_)
        return ESTALE;
    return readPointerState(config_.owner, config_.state, objects, value);
}

int PointerPumpButton::write(void *event, size_t capacity, void *context) noexcept {
    auto &self = *static_cast<PointerPumpButton *>(context);
    InputStateObjects objects;
    PointerStateValue cursor;
    if (const int error = self.current(objects, cursor))
        return error;
    double time;
    if (const int error = readEventClock(self.config_.clock, time))
        return error;
    if (const int error = self.current(objects, cursor))
        return error;
    const PointerEventRequest request{
        static_cast<uint32_t>(self.writingDown_ ? FM_LINUX_POINTER_PRESS : FM_LINUX_POINTER_RELEASE), self.button_,
        cursor.x, cursor.y, 0};
    return writePointerEvent(self.config_.event, event, capacity, request, time);
}

int PointerPumpButton::press(uint32_t button) {
    const auto thread = currentThread();
    if (thread != getpid() && thread != evaluationThread_)
        return EPERM;
    if (started_)
        return EALREADY;
    started_ = true;
    if (!entry_ || !validPointerPumpConfig(config_) || button < 1 || button > FM_LINUX_POINTER_BUTTONS)
        return record(EINVAL);
    InputStateObjects objects;
    PointerStateValue cursor;
    if (const int error = readPointerState(config_.owner, config_.state, objects, cursor))
        return record(error);
    button_ = button;
    mask_ = config_.state.masks[button - 1];
    global_ = objects.global;
    state_ = objects.state;
    writingDown_ = true;
    Busy busy(dispatching_);
    record(pump_.dispatch(config_.site, entry_, context_, write, this, press_, thread));
    owned_ = press_.delivered;
    return failure_;
}

int PointerPumpButton::release() {
    const auto thread = currentThread();
    if (thread != getpid() && thread != evaluationThread_)
        return EPERM;
    if (dispatching_)
        return EBUSY;
    if (!started_)
        return EINVAL;
    if (!owned_)
        return failure_;
    InputStateObjects objects;
    PointerStateValue cursor;
    if (const int error = current(objects, cursor))
        return record(error);
    if (!release_.delivered) {
        writingDown_ = false;
        Busy busy(dispatching_);
        record(pump_.dispatch(config_.site, entry_, context_, write, this, release_, thread));
        if (!release_.delivered)
            return failure_;
        if (const int error = current(objects, cursor))
            return record(error);
    }
    if (release_.returned && release_.emptyObserved && !(objects.held & mask_))
        owned_ = false;
    else
        record(EPROTO);
    return failure_;
}

PointerPump::PointerPump(EventPump &pump, const PointerPumpConfig &config, EventPump::Pump entry, void *context,
                         Guard guard, void *guardContext, pid_t evaluationThread)
    : pump_(pump), config_(config), entry_(entry), context_(context), guard_(guard), guardContext_(guardContext),
      evaluationThread_(evaluationThread) {}

int PointerPump::guarded() noexcept {
    if (!guard_)
        return EINVAL;
    try {
        return guard_(guardContext_);
    } catch (...) {
        return EIO;
    }
}

int PointerPump::begin(PointerStateValue &value) {
    const auto thread = currentThread();
    if (thread != getpid() && thread != evaluationThread_)
        return EPERM;
    if (!entry_ || !validPointerPumpConfig(config_))
        return EINVAL;
    if (const int error = guarded())
        return error;
    InputStateObjects objects;
    if (const int error = readPointerState(config_.owner, config_.state, objects, value))
        return error;
    global_ = objects.global;
    state_ = objects.state;
    return 0;
}

int PointerPump::current(PointerStateValue &value) {
    InputStateObjects objects;
    if (const int error = readInputState(config_.owner, objects))
        return error;
    if (objects.global != global_ || objects.state != state_)
        return ESTALE;
    return readPointerState(config_.owner, config_.state, objects, value);
}

int PointerPump::write(void *event, size_t capacity, void *context) noexcept {
    auto &self = *static_cast<PointerPump *>(context);
    if (const int error = self.guarded())
        return error;
    double time;
    if (const int error = readEventClock(self.config_.clock, time))
        return error;
    if (const int error = self.guarded())
        return error;
    PointerStateValue cursor;
    if (const int error = self.current(cursor))
        return error;
    auto request = self.request_;
    if (request.operation == FM_LINUX_POINTER_WHEEL) {
        request.x = cursor.x;
        request.y = cursor.y;
    }
    return writePointerEvent(self.config_.event, event, capacity, request, time);
}

int PointerPump::move(InputPosition position, PointerMoveProgress &progress) {
    progress = {};
    const auto thread = currentThread();
    if (thread != getpid() && thread != evaluationThread_)
        return EPERM;
    if (dispatching_)
        return EBUSY;
    if (position.x < 0 || position.y < 0)
        return ERANGE;
    Busy busy(dispatching_);
    PointerStateValue cursor;
    if (const int error = begin(cursor))
        return error;
    if (!cursor.inWindow) {
        request_ = {FM_LINUX_POINTER_ENTER, 0, 0, 0, 0};
        if (const int error = pump_.dispatch(config_.site, entry_, context_, write, this, progress.enter, thread))
            return error;
        if (const int error = guarded())
            return error;
        if (const int error = current(cursor))
            return error;
    }
    request_ = {FM_LINUX_POINTER_MOVE, 0, position.x, position.y, 0};
    return pump_.dispatch(config_.site, entry_, context_, write, this, progress.move, thread);
}

int PointerPump::wheel(int32_t direction, EventPumpProgress &progress) {
    progress = {};
    const auto thread = currentThread();
    if (thread != getpid() && thread != evaluationThread_)
        return EPERM;
    if (dispatching_)
        return EBUSY;
    if (direction != -1 && direction != 1)
        return EINVAL;
    Busy busy(dispatching_);
    PointerStateValue cursor;
    if (const int error = begin(cursor))
        return error;
    request_ = {FM_LINUX_POINTER_WHEEL, 0, 0, 0, direction};
    return pump_.dispatch(config_.site, entry_, context_, write, this, progress, thread);
}
