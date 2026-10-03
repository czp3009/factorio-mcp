#include "input_events.h"
#include "memory_read.h"
#include <cerrno>
#include <cmath>
#include <cstring>
#include <utility>

namespace {
bool object(uintptr_t address, uint32_t size) {
    return address % alignof(uintptr_t) == 0 && fm::addressRange(address, size);
}

bool same(const InputStateObjects &left, const InputStateObjects &right) {
    return left.global == right.global && left.state == right.state;
}
} // namespace

bool validMouseStateLayout(const FmLinuxMouseStateLayout &layout) {
    if (!object(layout.global, sizeof(uintptr_t)) || layout.globalSize < sizeof(uintptr_t) ||
        layout.globalSize > 64 * 1024 * 1024 || layout.stateSize < sizeof(uintptr_t) ||
        layout.stateSize > 16 * 1024 * 1024 || !fm::member(layout.stateMember, sizeof(uintptr_t), layout.globalSize) ||
        !fm::member(layout.heldMask, sizeof(uint32_t), layout.stateSize) ||
        layout.eventSize > FM_LINUX_INPUT_EVENT_BYTES || layout.press == layout.release)
        return false;
    const std::pair<uint32_t, uint32_t> fields[] = {
        {layout.eventType, sizeof(uint32_t)}, {layout.eventTime, sizeof(double)}, {layout.eventCode, sizeof(uint32_t)}};
    for (unsigned index = 0; index < 3; ++index) {
        const auto [offset, width] = fields[index];
        if (!fm::member(offset, width, layout.eventSize))
            return false;
        for (unsigned previous = 0; previous < index; ++previous) {
            const auto [other, size] = fields[previous];
            if (!(offset + width <= other || other + size <= offset))
                return false;
        }
        const auto code = layout.codes[index];
        const auto mask = layout.masks[index];
        if (!code || code > 255 || !mask || (mask & (mask - 1)))
            return false;
        for (unsigned previous = 0; previous < index; ++previous)
            if (layout.codes[previous] == code || layout.masks[previous] == mask)
                return false;
    }
    return true;
}

int readInputState(const FmLinuxMouseStateLayout &layout, InputStateObjects &output) {
    output = {};
    if (!validMouseStateLayout(layout))
        return EINVAL;
    InputStateObjects found;
    if (!fm::read(layout.global, found.global))
        return EFAULT;
    if (!found.global)
        return ENOENT;
    if (!object(found.global, layout.globalSize) || !fm::read(found.global + layout.stateMember, found.state))
        return EFAULT;
    if (!found.state)
        return ENOENT;
    if (!object(found.state, layout.stateSize) || !fm::read(found.state + layout.heldMask, found.held))
        return EFAULT;
    output = found;
    return 0;
}

int dispatchMouseState(const FmLinuxMouseStateLayout &layout, const InputStateFunctions &functions,
                       const InputStateObjects &objects, uint32_t code, bool pressed, double time,
                       InputDispatch &output) {
    output = {};
    if (!validMouseStateLayout(layout) || !functions.update || !functions.postUpdate || !std::isfinite(time) ||
        time < 0)
        return EINVAL;
    bool known = false;
    for (const auto candidate : layout.codes)
        known |= code == candidate;
    if (!known)
        return EINVAL;
    alignas(16) unsigned char event[FM_LINUX_INPUT_EVENT_BYTES]{};
    const auto type = pressed ? layout.press : layout.release;
    std::memcpy(event + layout.eventType, &type, sizeof(type));
    std::memcpy(event + layout.eventTime, &time, sizeof(time));
    std::memcpy(event + layout.eventCode, &code, sizeof(code));
    return dispatchInputStateEvent(layout, functions, objects, event, output);
}

int dispatchInputStateEvent(const FmLinuxMouseStateLayout &layout, const InputStateFunctions &functions,
                            const InputStateObjects &objects, const unsigned char (&event)[FM_LINUX_INPUT_EVENT_BYTES],
                            InputDispatch &output) {
    output = {};
    if (!validMouseStateLayout(layout) || !functions.update || !functions.postUpdate)
        return EINVAL;
    InputStateObjects current;
    auto error = readInputState(layout, current);
    if (error)
        return error;
    if (!same(objects, current))
        return ESTALE;
    output.updateEntered = true;
    try {
        functions.update(reinterpret_cast<void *>(objects.state), event);
        output.updateReturned = true;
    } catch (...) {
        error = EIO;
    }
    // Reacquire the authoritative owner before the next call; a callback may have replaced or destroyed it.
    const auto identityError = readInputState(layout, current);
    if (identityError || !same(objects, current))
        return error ? error : identityError ? identityError : ESTALE;
    output.postEntered = true;
    try {
        functions.postUpdate(reinterpret_cast<void *>(objects.state), event);
        output.postReturned = true;
    } catch (...) {
        if (!error)
            error = EIO;
    }
    const auto finalError = readInputState(layout, current);
    if (finalError || !same(objects, current))
        return error ? error : finalError ? finalError : ESTALE;
    return error;
}

int MouseButtonOwnership::record(int error) {
    if (error && !failure_)
        failure_ = error;
    return failure_;
}

bool MouseButtonOwnership::matches(const InputStateObjects &objects) const {
    return objects.global == globalIdentity_ && objects.state == stateIdentity_;
}

int MouseButtonOwnership::press(const FmLinuxMouseStateLayout &layout, const InputStateFunctions &functions,
                                uint32_t code, double time) {
    if (started_)
        return EALREADY;
    started_ = true;
    if (!validMouseStateLayout(layout))
        return record(EINVAL);
    for (unsigned index = 0; index < FM_LINUX_MOUSE_BUTTONS; ++index) {
        if (layout.codes[index] == code) {
            code_ = code;
            mask_ = layout.masks[index];
        }
    }
    if (!mask_)
        return record(EINVAL);
    InputStateObjects objects;
    if (const int error = readInputState(layout, objects))
        return record(error);
    globalIdentity_ = objects.global;
    stateIdentity_ = objects.state;
    owned_ = true;
    record(dispatchMouseState(layout, functions, objects, code_, true, time, press_));
    if (!press_.updateEntered)
        owned_ = false;
    return failure_;
}

int MouseButtonOwnership::reconcile(const FmLinuxMouseStateLayout &layout) {
    if (!started_)
        return EINVAL;
    if (!owned_)
        return failure_;
    InputStateObjects objects;
    const int error = readInputState(layout, objects);
    if (error == ENOENT || (!error && !matches(objects))) {
        // The old service is no longer authoritative; never release a button in its replacement.
        owned_ = false;
        return record(ESTALE);
    }
    if (error)
        return record(error);
    // This is resource cleanup verification, not gameplay-effect polling. An entered release is never replayed.
    if (release_.updateEntered && !(objects.held & mask_))
        owned_ = false;
    else if (release_.updateEntered)
        record(EPROTO);
    return failure_;
}

int MouseButtonOwnership::release(const FmLinuxMouseStateLayout &layout, const InputStateFunctions &functions,
                                  double time) {
    if (!started_)
        return EINVAL;
    reconcile(layout);
    if (!owned_ || release_.updateEntered)
        return failure_;
    InputStateObjects objects;
    if (const int error = readInputState(layout, objects))
        return record(error);
    if (!matches(objects)) {
        owned_ = false;
        return record(ESTALE);
    }
    record(dispatchMouseState(layout, functions, objects, code_, false, time, release_));
    return reconcile(layout);
}
