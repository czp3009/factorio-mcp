#include "frontend_input.h"
#include "protocol.h"
#include <algorithm>
#include <cstring>

void KeyGesture::begin(const uint32_t *values, unsigned length) {
    require(!running, "Another frontend key gesture is active");
    require(length && length <= keys.size(), "Invalid key chord size");
    for (unsigned i = 0; i < length; ++i) {
        require(std::find(values, values + i, values[i]) == values + i, "Duplicate key in chord");
    }
    std::copy(values, values + length, keys.begin());
    count = length;
    pressed = released = 0;
    admitting = running = true;
    cancelled = false;
}

void KeyGesture::cancel() {
    if (running) {
        cancelled = true;
        admitting = false;
    }
}

bool KeyGesture::active() const {
    return running;
}

bool KeyGesture::aborted() const {
    return cancelled;
}

bool KeyGesture::next(uint32_t &key, bool &down) {
    if (!running)
        return false;
    if (admitting && pressed < count) {
        key = keys[pressed++];
        down = true;
        return true;
    }
    admitting = false;
    if (released < pressed) {
        key = keys[pressed - ++released];
        down = false;
        return true;
    }
    // The pump has returned for another event, so the last up passed through normal processing.
    running = false;
    return false;
}

void writeKeyboardEvent(const Symbols &symbols, void *optional, uint32_t key, bool down) {
    const auto &layout = symbols.input;
    auto *storage = static_cast<unsigned char *>(optional);
    require(layout.supported && !storage[layout.optionalEngaged], "Expected an empty event optional");
    auto *event = storage + layout.optionalValue;
    const auto ticks = reinterpret_cast<uint32_t (*)()>(symbols.address[SdlTicks])();
    // Verified Win64 constructor: this in RCX, timestamp in XMM1, Event::Type in R8D.
    reinterpret_cast<void *(*)(void *, double, int)>(symbols.address[EventConstructor])(
        event, ticks / 1000.0, down ? layout.keyDown : layout.keyUp);
    memcpy(event + layout.keyboard + layout.scancode, &key, sizeof(key));
    storage[layout.optionalEngaged] = 1;
}
