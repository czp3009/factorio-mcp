#pragma once
#include "bridge.h"
#include <array>

// One finite chord. Every delivered down has a resident-owned up, even after cancellation.
class KeyGesture {
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

// Called only for an empty, game-constructed optional<Event> at the normal event pump.
void writeKeyboardEvent(const Symbols &symbols, void *optional, uint32_t key, bool down);
