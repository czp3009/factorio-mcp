#include "input_task_session.h"
#include <algorithm>
#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <sys/syscall.h>
#include <unistd.h>

namespace {
struct Lease {
    std::atomic_flag &gate;
    bool retained = false;

    ~Lease() {
        if (!retained)
            gate.clear(std::memory_order_release);
    }
};

std::vector<InputStep> steps(const FmLinuxInputTask &wire) {
    if (wire.count > FM_LINUX_INPUT_STEPS || wire.stopPrevious > 1 || wire.reserved || fm_linux_input_state(&wire) ||
        fm_linux_input_completed(&wire) || fm_linux_input_ticks(&wire))
        throw std::invalid_argument("Invalid input admission state");
    std::vector<InputStep> result;
    result.reserve(wire.count);
    for (uint32_t i = 0; i < wire.count; ++i) {
        const auto &row = wire.operations[i];
        if (row.count > FM_LINUX_INPUT_BUTTONS || row.motionCount > FM_LINUX_INPUT_MOTION || row.hasPosition > 1)
            throw std::invalid_argument("Invalid input operation bounds");
        InputStep step{row.ticks, {}, {}, row.wheel, {}};
        if (row.hasPosition)
            step.position = InputPosition{row.x, row.y};
        for (uint32_t button = 0; button < row.count; ++button) {
            const auto &value = row.buttons[button];
            if (value.device == static_cast<uint32_t>(InputDevice::Mouse) && (value.code < 1 || value.code > 5))
                throw std::invalid_argument("Invalid logical mouse button");
            step.buttons.push_back({static_cast<InputDevice>(value.device), value.code});
        }
        for (uint32_t point = 0; point < row.motionCount; ++point)
            step.motion.push_back({row.motion[point].tick, {row.motion[point].x, row.motion[point].y}});
        result.push_back(std::move(step));
    }
    InputSequence::validate(result);
    return result;
}
} // namespace

InputTaskSession::InputTaskSession(ViewLifetime &lifetime, InputTaskContext::Reader reader)
    : lifetime_(lifetime), reader_(reader) {}

int InputTaskSession::start(const FmLinuxInputContextConfig &config, pid_t owner, int descriptor, pid_t thread,
                            uintptr_t caller, InputEmitter &emitter) {
    if (syscall(SYS_gettid) != getpid())
        return EPERM;
    if (!reader_)
        return EINVAL;
    if (busy_.test_and_set(std::memory_order_acquire))
        return EBUSY;
    Lease lease{busy_};
    if (task_)
        return EBUSY;
    canceled_.store(false, std::memory_order_release);
    task_.emplace(lifetime_, reader_);
    owned_.store(true, std::memory_order_release);
    int error = task_->mapping.open(owner, descriptor);
    try {
        if (!error) {
            auto *wire = task_->mapping.task();
            task_->sequence.emplace(steps(*wire));
            if (fm_ipc_load(&wire->cancel))
                error = ECANCELED;
            if (!error && task_->sequence->state() == InputSequenceState::Succeeded) {
                fm_ipc_store(&wire->state, 2);
                task_->finalized = true;
                return closeFinished();
            }
            if (!error && (thread <= 0 || !caller || caller > INTPTR_MAX))
                error = EINVAL;
            InputContext current;
            if (!error)
                error = task_->context.bind(config, &wire->cancel, current);
            if (!error && (current.paused || current.stopped))
                error = EAGAIN;
            if (!error) {
                task_->evaluation.emplace(task_->context, *task_->sequence, emitter, thread, caller);
                thread_.store(thread, std::memory_order_release);
                task_->admitted = true;
                fm_ipc_store(&wire->state, 1);
            }
        }
    } catch (const std::invalid_argument &) {
        error = EINVAL;
    } catch (...) {
        error = EFAULT;
    }
    if (error)
        closeFinished();
    return error;
}

int InputTaskSession::validateContext() {
    if (!task_ || !task_->admitted || task_->finalized)
        return EINVAL;
    InputContext current;
    if (const int error = task_->context.read(current))
        return error;
    return current.paused || current.stopped ? EAGAIN : 0;
}

void InputTaskSession::observeCancellation() {
    if (!task_ || !task_->admitted || task_->finalized)
        return;
    bool alive = false;
    if (canceled_.load(std::memory_order_acquire) || fm_ipc_load(&task_->mapping.task()->cancel) ||
        task_->mapping.ownerAlive(alive) || !alive)
        task_->evaluation->cancel();
}

void InputTaskSession::publish() {
    if (!task_ || !task_->admitted || task_->finalized)
        return;
    auto *wire = task_->mapping.task();
    if (!wire)
        return;
    const auto &sequence = *task_->sequence;
    fm_ipc_store(&wire->completedOperations, static_cast<uint32_t>(sequence.completed()));
    fm_ipc_store64(&wire->evaluatedTicks, sequence.ticks());
    if (task_->evaluation->finished()) {
        const auto &reason = sequence.reason();
        const auto size = std::min(reason.size(), sizeof(wire->reason) - 1);
        std::memcpy(wire->reason, reason.data(), size);
        wire->reason[size] = 0;
        fm_ipc_store(&wire->state, sequence.state() == InputSequenceState::Succeeded ? 2 : 3);
        task_->finalized = true;
    }
}

int InputTaskSession::closeFinished() {
    if (!task_)
        return 0;
    if (task_->admitted && !task_->evaluation->finished())
        return EBUSY;
    const int error = task_->mapping.close();
    if (error)
        return error;
    task_->context.release();
    task_.reset();
    thread_.store(0, std::memory_order_release);
    owned_.store(false, std::memory_order_release);
    return 0;
}

int InputTaskSession::frontend() {
    if (syscall(SYS_gettid) != getpid())
        return EPERM;
    if (busy_.test_and_set(std::memory_order_acquire))
        return EBUSY;
    Lease lease{busy_};
    observeCancellation();
    if (task_ && task_->admitted && !task_->finalized) {
        task_->evaluation->frontend();
        publish();
    }
    return closeFinished();
}

uint64_t InputTaskSession::before(uintptr_t receiver, uintptr_t caller) {
    if (syscall(SYS_gettid) != thread_.load(std::memory_order_acquire) || busy_.test_and_set(std::memory_order_acquire))
        return 0;
    Lease lease{busy_};
    if (!task_ || !task_->admitted || task_->finalized)
        return 0;
    observeCancellation();
    const auto cookie = task_->evaluation->before(receiver, caller);
    publish();
    if (cookie > 1) {
        receiver_ = receiver;
        cookie_.store(cookie, std::memory_order_release);
        lease.retained = true;
    }
    return cookie;
}

void InputTaskSession::after(uintptr_t receiver, uint64_t cookie) {
    if (syscall(SYS_gettid) != thread_.load(std::memory_order_acquire) || cookie <= 1 ||
        cookie_.load(std::memory_order_acquire) != cookie || receiver_ != receiver)
        return;
    Lease lease{busy_};
    task_->evaluation->after(receiver, cookie);
    cookie_.store(0, std::memory_order_release);
    publish();
}

void InputTaskSession::aborted(uintptr_t receiver, uint64_t cookie) {
    if (syscall(SYS_gettid) != thread_.load(std::memory_order_acquire) || cookie <= 1 ||
        cookie_.load(std::memory_order_acquire) != cookie || receiver_ != receiver)
        return;
    Lease lease{busy_};
    task_->evaluation->aborted(receiver, cookie);
    cookie_.store(0, std::memory_order_release);
    publish();
}
