#pragma once
#include "ui_capture.h"

// Stable native references bind one selected widget to its original root. Only these owned list records, scalar
// layout data, and GUI identity survive a callback; borrow() reacquires every widget address in the current frame.
class UiTargetReference {
public:
    bool owned() const { return root_.owned() || widget_.owned(); }
    int attach(uintptr_t instance, const FmLinuxUiLayout &ui, const FmLinuxCaptureLayout &capture,
               const UiTarget &target);
    int borrow(uintptr_t instance, const FmLinuxUiLayout &ui, UiTarget &target) const;
    // Capture cleanup can still belong to this root after the selected widget was destroyed.
    int borrowRoot(uintptr_t instance, const FmLinuxUiLayout &ui, uintptr_t &gui, uintptr_t &root) const;
    int validateIdentity(uintptr_t gui, uintptr_t root, uintptr_t widget) const;
    int release();

private:
    TargetReference root_;
    TargetReference widget_;
    uintptr_t guiIdentity_ = 0;
    uint32_t targetable_ = 0;
    uint32_t guiSize_ = 0;
    uint32_t widgetSize_ = 0;
    uint32_t rootOffset_ = 0;
};
