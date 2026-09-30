#pragma once
#include <stddef.h>
#include <stdint.h>

#define FM_LINUX_EVENT_BYTES 256

typedef struct FmLinuxKeyboardEventLayout {
    uint32_t extent;
    uint32_t type;
    uint32_t time;
    uint32_t code;
    uint32_t emptyType;
    uint32_t press;
    uint32_t release;
    uint8_t defaults[FM_LINUX_EVENT_BYTES];
    uint8_t initialized[FM_LINUX_EVENT_BYTES];
} FmLinuxKeyboardEventLayout;

typedef struct FmLinuxEventClock {
    uint64_t ticks;
    double divisor;
} FmLinuxEventClock;

#ifdef __cplusplus
bool validKeyboardEventLayout(const FmLinuxKeyboardEventLayout &layout);
int readEventClock(const FmLinuxEventClock &clock, double &output);
// Borrow only the current poll's game-constructed empty Event. All rejection precedes any write.
int writeKeyboardEvent(const FmLinuxKeyboardEventLayout &layout, void *event, size_t capacity,
                       uint32_t code, bool down, double timestamp);

// Private storage for a scalar keyboard variant. The caller must prove that all native consumers read only
// the header, code and established defaults and that this variant has no owning payload. Zeroed spare bytes
// are storage hygiene, not inferred game defaults. No native constructor/destructor or allocation is invoked.
class KeyboardEventStorage {
public:
    KeyboardEventStorage() = default;
    KeyboardEventStorage(const KeyboardEventStorage &) = delete;
    KeyboardEventStorage &operator=(const KeyboardEventStorage &) = delete;
    int prepare(const FmLinuxKeyboardEventLayout &layout, uint32_t code, bool down, double timestamp);
    const void *data() const { return ready_ ? bytes_ : nullptr; }
    size_t size() const { return ready_ ? extent_ : 0; }

private:
    alignas(16) unsigned char bytes_[FM_LINUX_EVENT_BYTES]{};
    uint32_t extent_ = 0;
    bool ready_ = false;
};
#endif
