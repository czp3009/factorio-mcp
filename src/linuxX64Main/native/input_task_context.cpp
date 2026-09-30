#include "input_task_context.h"
#include <cerrno>
#include <stdexcept>

InputTaskContext::InputTaskContext(ViewLifetime &lifetime, Reader reader) : lifetime_(lifetime), reader_(reader) {}

int InputTaskContext::record(int error) {
    if (error && !failure_)
        failure_ = error;
    return failure_;
}

int InputTaskContext::bind(const FmLinuxInputContextConfig &config, const uint32_t *cancel, InputContext &output) {
    output = {};
    if (started_)
        return EALREADY;
    started_ = true;
    if (!reader_ || !cancel)
        return record(EINVAL);
    config_ = config;
    const auto generation = lifetime_.generation();
    InputContext found;
    if (const int error = reader_(config_, cancel, found))
        return record(error);
    if (!found.game || !found.source || !found.player || !found.map || !found.view)
        return record(ESTALE);
    if (const int error = lifetime_.bind(found.view, generation))
        return record(error);
    identity_ = {found.game, found.source, found.player, found.map, found.view};
    owned_ = true;
    output = found;
    return 0;
}

int InputTaskContext::read(InputContext &output) {
    output = {};
    if (failure_)
        return failure_;
    if (!owned_)
        return record(EINVAL);
    if (!lifetime_.valid(identity_.view))
        return record(ESTALE);
    // Cancellation belongs to the task. Context checks must also work while releasing owned input.
    const uint32_t cancel = 0;
    InputContext found;
    if (const int error = reader_(config_, &cancel, found))
        return record(error);
    if (found.game != identity_.game || found.source != identity_.source || found.player != identity_.player ||
        found.map != identity_.map || found.view != identity_.view || !lifetime_.valid(found.view))
        return record(ESTALE);
    output = found;
    return 0;
}

void InputTaskContext::release() {
    if (owned_)
        lifetime_.release();
    owned_ = false;
    identity_ = {};
}

GuardedInputEmitter::GuardedInputEmitter(InputTaskContext &context, InputEmitter &native)
    : context_(context), native_(native) {}

void GuardedInputEmitter::check() {
    InputContext current;
    if (context_.read(current))
        throw std::runtime_error("Input context changed or is unavailable");
    if (current.paused || current.stopped)
        throw std::runtime_error("Input requires a normally running world");
}

void GuardedInputEmitter::move(InputPosition position) {
    check();
    native_.move(position);
    check();
}

void GuardedInputEmitter::button(InputButton button, bool down) {
    if (down)
        check();
    native_.button(button, down);
    if (down)
        check();
}

void GuardedInputEmitter::wheel(int32_t direction) {
    check();
    native_.wheel(direction);
    check();
}
