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
        !fm::member(layout.key, sizeof(int32_t), layout.stride) || !fm::member(layout.held, 1, layout.stride) ||
        !(layout.key + sizeof(int32_t) <= layout.held || layout.held + 1 <= layout.key) ||
        !validKeyboardModifiers(owner, layout.modifiers))
        return false;
    const auto size = owner.stateSize - layout.map;
    for (const auto field : {layout.begin, layout.end}) {
        if (!fm::member(field, sizeof(uintptr_t), size))
            return false;
        const auto absolute = layout.map + field;
        if (!(absolute + sizeof(uintptr_t) <= layout.modifiers.state || layout.modifiers.state + 2 <= absolute))
            return false;
    }
    return fm::member(layout.begin, sizeof(uintptr_t), size) && fm::member(layout.end, sizeof(uintptr_t), size) &&
           layout.begin % alignof(uintptr_t) == 0 && layout.end % alignof(uintptr_t) == 0 && layout.begin != layout.end;
}

bool validKeyboardModifiers(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyboardModifiers &layout) {
    if (!validMouseStateLayout(owner) || !fm::member(layout.state, sizeof(uint16_t), owner.stateSize) ||
        !(owner.heldMask + sizeof(uint32_t) <= layout.state || layout.state + 2 <= owner.heldMask))
        return false;
    for (unsigned index = 0; index < 12; ++index) {
        if (!layout.keys[index] || layout.keys[index] > uint32_t(std::numeric_limits<int32_t>::max()))
            return false;
        for (unsigned previous = 0; previous < index; ++previous)
            if (layout.keys[index] == layout.keys[previous])
                return false;
    }
    return true;
}

int readKeyboardModifiers(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyboardModifiers &layout,
                          uintptr_t global, uintptr_t state, uint32_t code, bool down, uint16_t &output) {
    output = 0;
    if (!validKeyboardModifiers(owner, layout))
        return EINVAL;
    InputStateObjects current;
    if (const int error = readInputState(owner, current))
        return error;
    if (current.global != global || current.state != state)
        return ESTALE;
    uint16_t modifiers;
    if (!fm::read(current.state + layout.state, modifiers))
        return EFAULT;
    // Public SDL3 SDL_Keymod ABI; the selected executable's converter establishes this field's identity.
    // https://github.com/libsdl-org/SDL/blob/release-3.2.28/include/SDL3/SDL_keycode.h
    constexpr uint16_t masks[]{0x0001, 0x0002, 0x0040, 0x0080, 0x0100, 0x0200,
                               0x0400, 0x0800, 0x4000, 0x1000, 0x2000, 0x8000};
    for (unsigned index = 0; index < 12; ++index)
        if (code == layout.keys[index]) {
            if (index < 9)
                modifiers = down ? modifiers | masks[index] : modifiers & ~masks[index];
            else if (down)
                modifiers ^= masks[index];
        }
    output = modifiers;
    return 0;
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
            uint8_t held;
            if (!fm::read(record + layout.held, held))
                return EFAULT;
            if (held > 1)
                return EPROTO;
            found = {true, held != 0};
        }
    }
    objects = current;
    output = found;
    return 0;
}

bool validKeyboardStateConfig(const FmLinuxMouseStateLayout &owner, const FmLinuxKeyboardStateConfig &config) {
    if (!validKeyStateLayout(owner, config.layout) || config.press == config.release ||
        !fm::member(config.eventCode, sizeof(uint32_t), owner.eventSize) ||
        !fm::member(config.eventModifiers, sizeof(uint16_t), owner.eventSize))
        return false;
    for (const auto field :
         {std::pair{owner.eventType, uint32_t(sizeof(uint32_t))}, std::pair{owner.eventTime, uint32_t(sizeof(double))},
          std::pair{config.eventModifiers, uint32_t(sizeof(uint16_t))}})
        if (!(config.eventCode + sizeof(uint32_t) <= field.first || field.first + field.second <= config.eventCode))
            return false;
    for (const auto field :
         {std::pair{owner.eventType, uint32_t(sizeof(uint32_t))}, std::pair{owner.eventTime, uint32_t(sizeof(double))}})
        if (!(config.eventModifiers + sizeof(uint16_t) <= field.first ||
              field.first + field.second <= config.eventModifiers))
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
    uint16_t modifiers;
    if (const int error = readKeyboardModifiers(owner, config.layout.modifiers, objects.global, objects.state, code,
                                                pressed, modifiers))
        return error;
    alignas(16) unsigned char event[FM_LINUX_INPUT_EVENT_BYTES]{};
    const auto type = pressed ? config.press : config.release;
    std::memcpy(event + owner.eventType, &type, sizeof(type));
    std::memcpy(event + owner.eventTime, &time, sizeof(time));
    std::memcpy(event + config.eventCode, &code, sizeof(code));
    std::memcpy(event + config.eventModifiers, &modifiers, sizeof(modifiers));
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
