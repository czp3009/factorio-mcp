#include "ui_reference.h"
#include "memory_read.h"
#include <cerrno>

int UiTargetReference::attach(uintptr_t instance, const FmLinuxUiLayout &ui, const FmLinuxCaptureLayout &capture,
                            const UiTarget &target) {
    if (owned())
        return EALREADY;
    if (!validCaptureLayout(capture, ui))
        return EINVAL;
    if (const int error = liveUiTarget(instance, ui, target))
        return error;
    guiIdentity_ = target.gui;
    targetable_ = capture.widgetTargetable;
    guiSize_ = ui.guiSize;
    widgetSize_ = ui.widgetSize;
    rootOffset_ = ui.root;
    if (const int error = root_.attach(target.root + targetable_, capture.targeter))
        return error;
    return widget_.attach(target.widget + targetable_, capture.targeter);
}

int UiTargetReference::validateIdentity(uintptr_t gui, uintptr_t root, uintptr_t widget) const {
    if (!owned() || gui != guiIdentity_)
        return ESTALE;
    uintptr_t rootTarget, widgetTarget;
    if (const int error = root_.borrow(rootTarget))
        return error;
    if (!rootTarget || rootTarget < targetable_ || rootTarget - targetable_ != root)
        return ESTALE;
    if (const int error = widget_.borrow(widgetTarget))
        return error;
    if (!widgetTarget || widgetTarget < targetable_ || widgetTarget - targetable_ != widget)
        return ENOENT;
    return 0;
}

int UiTargetReference::borrow(uintptr_t instance, const FmLinuxUiLayout &ui, UiTarget &target) const {
    target = {};
    uintptr_t gui, root, widgetTarget;
    if (const int error = borrowRoot(instance, ui, gui, root))
        return error;
    if (const int error = widget_.borrow(widgetTarget))
        return error;
    if (const int error = validateIdentity(gui, root, widgetTarget >= targetable_ ? widgetTarget - targetable_ : 0))
        return error;
    UiTarget current{gui, root, widgetTarget - targetable_, {}, this};
    if (const int error = liveUiTarget(instance, ui, current))
        return error;
    target = current;
    return 0;
}

int UiTargetReference::borrowRoot(uintptr_t instance, const FmLinuxUiLayout &ui, uintptr_t &gui, uintptr_t &root) const {
    gui = 0;
    root = 0;
    if (!validUiLayout(ui) || ui.guiSize != guiSize_ || ui.widgetSize != widgetSize_ || ui.root != rootOffset_)
        return EINVAL;
    uintptr_t currentGui, currentRoot, rootTarget;
    if (!fm::read(instance, currentGui))
        return EFAULT;
    if (!currentGui || currentGui != guiIdentity_)
        return ESTALE;
    if (!fm::addressRange(currentGui, ui.guiSize) || !fm::read(currentGui + ui.root, currentRoot))
        return EFAULT;
    if (const int error = root_.borrow(rootTarget))
        return error;
    if (!rootTarget || rootTarget < targetable_ || rootTarget - targetable_ != currentRoot)
        return ESTALE;
    gui = currentGui;
    root = currentRoot;
    return 0;
}

int UiTargetReference::release() {
    const int widgetError = widget_.release();
    const int rootError = root_.release();
    if (!owned()) {
        guiIdentity_ = 0;
        targetable_ = 0;
        guiSize_ = 0;
        widgetSize_ = 0;
        rootOffset_ = 0;
    }
    return widgetError ? widgetError : rootError;
}
