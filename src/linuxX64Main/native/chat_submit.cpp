#include "chat_submit.h"
#include <cerrno>
#include <cstring>
#include <sys/syscall.h>
#include <unistd.h>

int ChatSubmitCommand::record(int error) {
    if (error && !failure_)
        failure_ = error;
    return failure_;
}

int ChatSubmitCommand::start(const ChatSubmitConfig &config, const char *text, size_t size,
                             Resolve resolve, void *context) {
    if (syscall(SYS_gettid) != getpid())
        return EPERM;
    if (started_)
        return EALREADY;
    started_ = true;
    if (!resolve || !text || !size || size > FM_LINUX_CHAT_BYTES ||
        std::memchr(text, 0, size) || std::memchr(text, '\r', size) || std::memchr(text, '\n', size) ||
        !config.stringSize || config.stringSize > sizeof(string_) ||
        !config.actionSize || config.actionSize > sizeof(action_) || config.actionType > UINT16_MAX ||
        !config.constructString || !config.assignString || !config.destroyString ||
        !config.constructAction || !config.destroyAction || !config.submit) {
        finished_ = true;
        return record(EINVAL);
    }
    size_t first = 0;
    while (first < size && (text[first] == ' ' || text[first] == '\t' || text[first] == '\v' || text[first] == '\f'))
        ++first;
    if (first == size || text[first] == '/') {
        finished_ = true;
        return record(EINVAL);
    }
    config_ = config;
    busy_ = true;
    auto permitted = [&]() {
        void *player = nullptr;
        const int error = resolve(context, player);
        if (error || !player)
            record(error ? error : ESTALE);
        return failure_ ? nullptr : player;
    };
    try {
        if (permitted()) {
            config_.constructString(string_);
            stringOwned_ = true;
            config_.assignString(string_, text, size);
            if (permitted()) {
                config_.constructAction(action_, config_.actionType, string_);
                actionOwned_ = true;
                if (void *player = permitted()) {
                    submitEntered_ = true;
                    config_.submit(player, action_);
                    submitReturned_ = true;
                }
            }
        }
    } catch (...) {
        record(EFAULT);
    }
    cleanup();
    busy_ = false;
    return failure_;
}

int ChatSubmitCommand::cleanup() {
    if (actionOwned_ && !actionDestroyEntered_) {
        actionDestroyEntered_ = true;
        try {
            config_.destroyAction(action_);
            actionOwned_ = false;
        } catch (...) {
            record(EFAULT);
        }
    }
    // The constructed action owns its copied/moved payload; it must not borrow the source string.
    if (stringOwned_ && !stringDestroyEntered_) {
        stringDestroyEntered_ = true;
        try {
            config_.destroyString(string_);
            stringOwned_ = false;
        } catch (...) {
            record(EFAULT);
        }
    }
    finished_ = !actionOwned_ && !stringOwned_;
    return failure_;
}

int ChatSubmitCommand::finish() {
    if (syscall(SYS_gettid) != getpid())
        return EPERM;
    if (busy_)
        return EBUSY;
    if (!started_)
        return EINVAL;
    busy_ = true;
    cleanup();
    busy_ = false;
    return failure_;
}
