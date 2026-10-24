#include "keyboard_event.h"
#include <cerrno>
#include <cmath>
#include <cstring>
#include <limits>

namespace {
void writePayload(const FmLinuxKeyboardEventLayout &layout, unsigned char *bytes, uint32_t code, bool down,
                  double timestamp, uint16_t modifiers) {
    const uint32_t kind = down ? layout.press : layout.release;
    for (unsigned byte = 0; byte < layout.extent; ++byte)
        if (layout.initialized[byte])
            bytes[byte] = layout.defaults[byte];
    std::memcpy(bytes + layout.type, &kind, sizeof(kind));
    std::memcpy(bytes + layout.time, &timestamp, sizeof(timestamp));
    std::memcpy(bytes + layout.code, &code, sizeof(code));
    std::memcpy(bytes + layout.modifiers, &modifiers, sizeof(modifiers));
}
} // namespace

int readEventClock(const FmLinuxEventClock &clock, double &output) {
    if (!clock.ticks || !std::isfinite(clock.divisor) || clock.divisor <= 0)
        return EINVAL;
    double value;
    try {
        // Public SDL3 C ABI: Uint64 SDL_GetTicksNS(void). The decoded Event producer supplies the scale.
        value = reinterpret_cast<uint64_t (*)()>(clock.ticks)() / clock.divisor;
    } catch (...) {
        return EIO;
    }
    if (!std::isfinite(value) || value < 0)
        return ERANGE;
    output = value;
    return 0;
}

bool validKeyboardEventLayout(const FmLinuxKeyboardEventLayout &layout) {
    if (!layout.extent || layout.extent > FM_LINUX_EVENT_BYTES || layout.press == layout.release ||
        layout.emptyType == layout.press || layout.emptyType == layout.release)
        return false;
    const uint32_t offsets[] = {layout.type, layout.time, layout.code, layout.modifiers};
    const uint32_t widths[] = {4, 8, 4, 2};
    for (unsigned index = 0; index < 4; ++index) {
        if (widths[index] > layout.extent || offsets[index] > layout.extent - widths[index])
            return false;
        for (unsigned other = 0; other < index; ++other)
            if (offsets[index] < offsets[other] + widths[other] && offsets[other] < offsets[index] + widths[index])
                return false;
    }
    for (unsigned byte = 0; byte < FM_LINUX_EVENT_BYTES; ++byte) {
        if (layout.initialized[byte] > 1 || (byte >= layout.extent && layout.initialized[byte]))
            return false;
        if (layout.initialized[byte])
            for (unsigned index = 0; index < 4; ++index)
                if (byte >= offsets[index] && byte < offsets[index] + widths[index])
                    return false;
    }
    return true;
}

int writeKeyboardEvent(const FmLinuxKeyboardEventLayout &layout, void *event, size_t capacity, uint32_t code, bool down,
                       double timestamp, uint16_t modifiers) {
    if (!validKeyboardEventLayout(layout) || !event || capacity < layout.extent)
        return EINVAL;
    if (!std::isfinite(timestamp) || timestamp < 0)
        return ERANGE;
    auto *bytes = static_cast<unsigned char *>(event);
    uint32_t type;
    uint64_t time;
    std::memcpy(&type, bytes + layout.type, sizeof(type));
    std::memcpy(&time, bytes + layout.time, sizeof(time));
    if (type != layout.emptyType || time != 0)
        return ESTALE;
    writePayload(layout, bytes, code, down, timestamp, modifiers);
    return 0;
}

int KeyboardEventStorage::prepare(const FmLinuxKeyboardEventLayout &layout, uint32_t code, bool down, double timestamp,
                                  uint16_t modifiers) {
    if (ready_)
        return EALREADY;
    if (!validKeyboardEventLayout(layout) || !code || code > static_cast<uint32_t>(std::numeric_limits<int32_t>::max()))
        return EINVAL;
    if (!std::isfinite(timestamp) || timestamp < 0)
        return ERANGE;
    writePayload(layout, bytes_, code, down, timestamp, modifiers);
    extent_ = layout.extent;
    ready_ = true;
    return 0;
}
