#pragma once
#include <algorithm>
#include <array>
#include <cstdint>
#include <stdexcept>

// One finite chord. Every delivered down has a resident-owned up, even after cancellation.
class KeyGesture {
    static void require(bool condition, const char *message) {
        if (!condition)
            throw std::runtime_error(message);
    }

    std::array<uint32_t, 8> keys{};
    unsigned count{}, pressed{}, released{};
    bool admitting{}, running{}, cancelled{};

public:
    void begin(const uint32_t *values, unsigned length);
    void cancel();
    bool active() const;
    bool aborted() const;
    bool next(uint32_t &key, bool &down);
};

inline void KeyGesture::begin(const uint32_t *values, unsigned length) {
    require(!running, "Another frontend key gesture is active");
    require(values && length && length <= keys.size(), "Invalid key chord size");
    for (unsigned i = 0; i < length; ++i) {
        require(std::find(values, values + i, values[i]) == values + i, "Duplicate key in chord");
    }
    std::copy(values, values + length, keys.begin());
    count = length;
    pressed = released = 0;
    admitting = running = true;
    cancelled = false;
}

inline void KeyGesture::cancel() {
    if (running) {
        cancelled = true;
        admitting = false;
    }
}

inline bool KeyGesture::active() const {
    return running;
}

inline bool KeyGesture::aborted() const {
    return cancelled;
}

inline bool KeyGesture::next(uint32_t &key, bool &down) {
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

