#include "key_state.h"
#include "memory_read.h"
#include <cerrno>
#include <cmath>
#include <cstring>
#include <initializer_list>
#include <limits>
#include <utility>

bool validKeyStateLayout(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyStateLayout &layout) {
    if (!validMouseStateLayout(owner) || layout.map > owner.stateSize || layout.map % alignof(uintptr_t) ||
        layout.stride < sizeof(int32_t) || layout.stride > 512 || layout.stride % alignof(uintptr_t) ||
        !layout.valueSize || layout.valueSize > FM_LINUX_KEY_VALUE_BYTES ||
        !fm::member(layout.key, sizeof(int32_t), layout.stride) ||
        !fm::member(layout.value, layout.valueSize, layout.stride) || !fm::member(layout.held, 1, layout.valueSize) ||
        !fm::member(layout.clear, 1, layout.valueSize) || layout.held == layout.clear ||
        !(layout.key + sizeof(int32_t) <= layout.value || layout.value + layout.valueSize <= layout.key))
        return false;
    const auto size = owner.stateSize - layout.map;
    return fm::member(layout.begin, sizeof(uintptr_t), size) && fm::member(layout.end, sizeof(uintptr_t), size) &&
           layout.begin % alignof(uintptr_t) == 0 && layout.end % alignof(uintptr_t) == 0 && layout.begin != layout.end;
}

int readKeyState(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyStateLayout &layout, int32_t code,
                 InputStateObjects &objects, KeyStateValue &output) {
    objects = {};
    output = {};
    if (!validKeyStateLayout(owner, layout))
        return EINVAL;
    InputStateObjects current;
    if (const auto error = readInputState(owner, current))
        return error;
    const auto map = current.state + layout.map;
    uintptr_t begin, end;
    if (!fm::read(map + layout.begin, begin) || !fm::read(map + layout.end, end))
        return EFAULT;
    if ((!begin && end) || begin % alignof(uintptr_t) || end < begin || (end - begin) % layout.stride ||
        (end - begin) / layout.stride > FM_LINUX_KEY_RECORDS)
        return EPROTO;
    const auto count = (end - begin) / layout.stride;
    if (count && !fm::addressRange(begin, end - begin))
        return EFAULT;
    int32_t previous = std::numeric_limits<int32_t>::min();
    KeyStateValue found;
    for (uintptr_t index = 0; index < count; ++index) {
        const auto record = begin + index * layout.stride;
        int32_t key;
        if (!fm::read(record + layout.key, key))
            return EFAULT;
        if (index && key <= previous)
            return EPROTO;
        previous = key;
        if (key == code) {
            uint8_t held, blocked;
            if (!fm::read(record + layout.value + layout.held, held) ||
                !fm::read(record + layout.value + layout.clear, blocked))
                return EFAULT;
            if (held > 1 || blocked > 1)
                return EPROTO;
            found = {true, held != 0, blocked != 0};
        }
    }
    objects = current;
    output = found;
    return 0;
}

bool validKeyboardStateConfig(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyboardStateConfig &config) {
    if (!validKeyStateLayout(owner, config.layout) || config.press == config.release ||
        !fm::member(config.eventCode, sizeof(uint32_t), owner.eventSize))
        return false;
    for (const auto field :
         {std::pair{owner.eventType, uint32_t(sizeof(uint32_t))}, std::pair{owner.eventTime, uint32_t(sizeof(double))}})
        if (!(config.eventCode + sizeof(uint32_t) <= field.first || field.first + field.second <= config.eventCode))
            return false;
    for (unsigned index = 0; index < 3; ++index) {
        if (config.codes[index] > uint32_t(std::numeric_limits<int32_t>::max()))
            return false;
        for (unsigned previous = 0; previous < index; ++previous)
            if (config.codes[index] == config.codes[previous])
                return false;
    }
    return true;
}

int dispatchKeyState(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyboardStateConfig &config,
                     const InputStateFunctions &functions, const InputStateObjects &objects, uint32_t code,
                     bool pressed, double time, InputDispatch &output) {
    output = {};
    if (!validKeyboardStateConfig(owner, config) || !std::isfinite(time) || time < 0)
        return EINVAL;
    bool known = false;
    for (const auto candidate : config.codes)
        known |= candidate == code;
    if (!known)
        return EINVAL;
    alignas(16) unsigned char event[FM_LINUX_INPUT_EVENT_BYTES]{};
    const auto type = pressed ? config.press : config.release;
    std::memcpy(event + owner.eventType, &type, sizeof(type));
    std::memcpy(event + owner.eventTime, &time, sizeof(time));
    std::memcpy(event + config.eventCode, &code, sizeof(code));
    return dispatchInputStateEvent(owner, functions, objects, event, output);
}

int KeyboardKeyOwnership::record(int error) {
    if (error && !failure_)
        failure_ = error;
    return failure_;
}

bool KeyboardKeyOwnership::matches(const InputStateObjects &objects) const {
    return objects.global == globalIdentity_ && objects.state == stateIdentity_;
}

int KeyboardKeyOwnership::press(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyboardStateConfig &config,
                                const InputStateFunctions &functions, uint32_t code, double time) {
    if (started_)
        return EALREADY;
    started_ = true;
    if (!validKeyboardStateConfig(owner, config))
        return record(EINVAL);
    bool known = false;
    for (const auto candidate : config.codes)
        known |= candidate == code;
    if (!known)
        return record(EINVAL);
    InputStateObjects objects;
    KeyStateValue value;
    if (const auto error = readKeyState(owner, config.layout, int32_t(code), objects, value))
        return record(error);
    code_ = code;
    globalIdentity_ = objects.global;
    stateIdentity_ = objects.state;
    owned_ = true;
    record(dispatchKeyState(owner, config, functions, objects, code_, true, time, press_));
    if (!press_.updateEntered)
        owned_ = false;
    if (!failure_) {
        // The dispatched update must leave a valid record in the same input service.
        record(readKeyState(owner, config.layout, int32_t(code_), objects, value));
        if (!failure_ && (!matches(objects) || !value.present))
            record(EPROTO);
    }
    return failure_;
}

int KeyboardKeyOwnership::reconcile(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyboardStateConfig &config) {
    if (!started_)
        return EINVAL;
    if (!owned_)
        return failure_;
    InputStateObjects objects;
    // Reconcile service identity before inspecting the map, including an unreadable replacement map.
    const auto error = readInputState(owner, objects);
    if (error == ENOENT || (!error && !matches(objects))) {
        owned_ = false;
        return record(ESTALE);
    }
    if (error)
        return record(error);
    if (release_.updateEntered) {
        KeyStateValue value;
        if (const auto readError = readKeyState(owner, config.layout, int32_t(code_), objects, value))
            return record(readError);
        if (!matches(objects)) {
            owned_ = false;
            return record(ESTALE);
        }
        if (!value.held)
            owned_ = false;
        else
            record(EPROTO);
    }
    return failure_;
}

int KeyboardKeyOwnership::release(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyboardStateConfig &config,
                                  const InputStateFunctions &functions, double time) {
    if (!started_)
        return EINVAL;
    reconcile(owner, config);
    if (!owned_ || release_.updateEntered)
        return failure_;
    InputStateObjects objects;
    if (const auto error = readInputState(owner, objects))
        return record(error);
    if (!matches(objects)) {
        owned_ = false;
        return record(ESTALE);
    }
    record(dispatchKeyState(owner, config, functions, objects, code_, false, time, release_));
    return reconcile(owner, config);
}
