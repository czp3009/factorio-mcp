#include "mouse_input_event.h"
#include "memory_read.h"
#include <array>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <utility>

namespace {
bool member(uint32_t offset, uint32_t width, uint32_t extent) {
    return width <= extent && offset <= extent - width;
}
} // namespace

bool validPointerStateLayout(const FmLinuxMouseStateLayout &owner, const FmLinuxPointerStateLayout &layout) {
    if (!validMouseStateLayout(owner) || !fm::member(layout.position, 8, owner.stateSize) ||
        !fm::member(layout.inWindow, 1, owner.stateSize) ||
        (layout.inWindow >= layout.position && layout.inWindow - layout.position < 8) ||
        (layout.position < owner.heldMask + 4 && owner.heldMask < layout.position + 8) ||
        (layout.inWindow >= owner.heldMask && layout.inWindow - owner.heldMask < 4))
        return false;
    for (unsigned index = 0; index < FM_LINUX_POINTER_BUTTONS; ++index) {
        const auto mask = layout.masks[index];
        if (!mask || (mask & (mask - 1)))
            return false;
        for (unsigned previous = 0; previous < index; ++previous)
            if (layout.masks[previous] == mask)
                return false;
    }
    return true;
}

int readPointerState(const FmLinuxMouseStateLayout &owner, const FmLinuxPointerStateLayout &layout,
                     InputStateObjects &objects, PointerStateValue &output) {
    objects = {};
    output = {};
    if (!validPointerStateLayout(owner, layout))
        return EINVAL;
    InputStateObjects found;
    const auto error = readInputState(owner, found);
    if (error)
        return error;

    struct Position {
        int32_t x, y;
    } position;

    uint8_t inWindow;
    if (!fm::read(found.state + layout.position, position) || !fm::read(found.state + layout.inWindow, inWindow))
        return EFAULT;
    if (inWindow > 1)
        return EINVAL;
    objects = found;
    output = {position.x, position.y, inWindow != 0};
    return 0;
}

bool validPointerEventLayout(const FmLinuxPointerEventLayout &layout) {
    if (!layout.extent || layout.extent > FM_LINUX_EVENT_BYTES)
        return false;
    for (unsigned i = 0; i < FM_LINUX_POINTER_BUTTONS; ++i) {
        if (!layout.codes[i] || layout.codes[i] > 255)
            return false;
        for (unsigned j = 0; j < i; ++j)
            if (layout.codes[i] == layout.codes[j])
                return false;
    }
    for (unsigned operation = 0; operation < FM_LINUX_POINTER_CASES; ++operation) {
        const auto &item = layout.cases[operation];
        if (item.kind == layout.emptyType)
            return false;
        for (unsigned previous = 0; previous < operation; ++previous)
            if (layout.cases[previous].kind == item.kind)
                return false;
        const bool positioned = operation != FM_LINUX_POINTER_ENTER;
        const bool button = operation == FM_LINUX_POINTER_PRESS || operation == FM_LINUX_POINTER_RELEASE;
        const bool wheel = operation == FM_LINUX_POINTER_WHEEL;
        if ((item.x != FM_LINUX_POINTER_NO_FIELD) != positioned ||
            (item.y != FM_LINUX_POINTER_NO_FIELD) != positioned || (item.code != FM_LINUX_POINTER_NO_FIELD) != button ||
            (item.wheel != FM_LINUX_POINTER_NO_FIELD) != wheel || (item.wheelY != FM_LINUX_POINTER_NO_FIELD) != wheel)
            return false;
        const std::array<std::pair<uint32_t, uint32_t>, 7> fields{{
            {layout.type, 4},
            {layout.time, 8},
            {item.x, 4},
            {item.y, 4},
            {item.code, 4},
            {item.wheel, 4},
            {item.wheelY, 4},
        }};
        for (unsigned i = 0; i < fields.size(); ++i) {
            const auto [offset, width] = fields[i];
            if (offset == FM_LINUX_POINTER_NO_FIELD && i >= 2)
                continue;
            if (!member(offset, width, layout.extent))
                return false;
            for (unsigned j = 0; j < i; ++j) {
                const auto [other, size] = fields[j];
                if (other != FM_LINUX_POINTER_NO_FIELD && offset < other + size && other < offset + width)
                    return false;
            }
        }
        for (unsigned byte = 0; byte < FM_LINUX_EVENT_BYTES; ++byte) {
            if (item.initialized[byte] > 1 || (byte >= layout.extent && item.initialized[byte]))
                return false;
            if (item.initialized[byte])
                for (const auto &[offset, width] : fields)
                    if (offset != FM_LINUX_POINTER_NO_FIELD && byte >= offset && byte - offset < width)
                        return false;
        }
    }
    return true;
}

int writePointerEvent(const FmLinuxPointerEventLayout &layout, void *event, size_t capacity,
                      const PointerEventRequest &request, double timestamp) noexcept {
    if (!validPointerEventLayout(layout) || !event || capacity < layout.extent ||
        request.operation >= FM_LINUX_POINTER_CASES)
        return EINVAL;
    if (!std::isfinite(timestamp) || timestamp < 0)
        return ERANGE;
    const bool button = request.operation == FM_LINUX_POINTER_PRESS || request.operation == FM_LINUX_POINTER_RELEASE;
    if (button ? request.button < 1 || request.button > FM_LINUX_POINTER_BUTTONS : request.button != 0)
        return EINVAL;
    if (request.operation == FM_LINUX_POINTER_WHEEL ? request.wheel != -1 && request.wheel != 1 : request.wheel != 0)
        return EINVAL;
    if (request.operation == FM_LINUX_POINTER_MOVE && (request.x < 0 || request.y < 0))
        return ERANGE;
    if (request.operation == FM_LINUX_POINTER_ENTER && (request.x != 0 || request.y != 0))
        return EINVAL;
    auto *bytes = static_cast<unsigned char *>(event);
    uint32_t kind;
    uint64_t time;
    std::memcpy(&kind, bytes + layout.type, sizeof(kind));
    std::memcpy(&time, bytes + layout.time, sizeof(time));
    if (kind != layout.emptyType || time != 0)
        return ESTALE;

    const auto &item = layout.cases[request.operation];
    for (unsigned byte = 0; byte < layout.extent; ++byte)
        if (item.initialized[byte])
            bytes[byte] = item.defaults[byte];
    std::memcpy(bytes + layout.type, &item.kind, sizeof(item.kind));
    std::memcpy(bytes + layout.time, &timestamp, sizeof(timestamp));
    if (item.x != FM_LINUX_POINTER_NO_FIELD) {
        std::memcpy(bytes + item.x, &request.x, sizeof(request.x));
        std::memcpy(bytes + item.y, &request.y, sizeof(request.y));
    }
    if (button) {
        const uint32_t code = layout.codes[request.button - 1];
        std::memcpy(bytes + item.code, &code, sizeof(code));
    }
    if (request.operation == FM_LINUX_POINTER_WHEEL) {
        std::memcpy(bytes + item.wheel, &request.wheel, sizeof(request.wheel));
        std::memcpy(bytes + item.wheelY, &request.wheel, sizeof(request.wheel));
    }
    return 0;
}
