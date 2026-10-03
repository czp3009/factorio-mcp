#pragma once
#include "bridge.h"
#include <functional>

// A direct widget gesture can transfer native GUI capture to another widget.
// Its release belongs to the same safe point, even if the original target disappears.
class UiCapture {
    const Symbols &symbols;
    void *gui;
    std::function<bool()> current;
    std::function<bool(void *)> live;
    bool finished{};
    uintptr_t target() const;

  public:
    UiCapture(const Symbols &symbols, void *gui, std::function<bool()> current, std::function<bool(void *)> live);
    void release(const void *absoluteEvent, void *alreadyReleased);
};
