#include "ui_modal.h"
#include "memory_read.h"
#include <array>
#include <cerrno>

bool validModalLayout(const FmLinuxModalLayout &modal, const FmLinuxUiLayout &ui) {
    return validUiLayout(ui) && modal.guiSize == ui.guiSize && modal.widgetSize == ui.widgetSize &&
        modal.begin != modal.end && modal.begin % alignof(uintptr_t) == 0 && modal.end % alignof(uintptr_t) == 0 &&
        fm::member(modal.begin, sizeof(uintptr_t), modal.guiSize) &&
        fm::member(modal.end, sizeof(uintptr_t), modal.guiSize) &&
        modal.stride >= sizeof(uintptr_t) && modal.stride <= 256 && modal.stride % alignof(uintptr_t) == 0 &&
        modal.target % alignof(uintptr_t) == 0 && fm::member(modal.target, sizeof(uintptr_t), modal.stride) &&
        fm::member(modal.widgetTargetable, sizeof(uintptr_t), modal.widgetSize) &&
        modal.parent == ui.rectangle.parent;
}

int checkUiModal(uintptr_t guiInstance, const FmLinuxUiLayout &ui, const FmLinuxModalLayout &modal,
                 const UiTarget &target) {
    if (!validModalLayout(modal, ui))
        return EINVAL;
    if (const int error = liveUiTarget(guiInstance, ui, target))
        return error;
    uintptr_t begin, end;
    if (!fm::read(target.gui + modal.begin, begin) || !fm::read(target.gui + modal.end, end) ||
        begin > end || (end - begin) % modal.stride || (begin && begin % alignof(uintptr_t)) || (!begin && end))
        return EFAULT;
    const uintptr_t count = (end - begin) / modal.stride;
    if (count > FM_LINUX_MAX_NODES)
        return E2BIG;
    if (count && !fm::addressRange(begin, end - begin))
        return EFAULT;
    uintptr_t top = 0;
    for (uintptr_t index = count; index; --index) {
        if (!fm::read(begin + (index - 1) * modal.stride + modal.target, top))
            return EFAULT;
        if (top)
            break;
    }
    if (!top)
        return 0;
    if (top < modal.widgetTargetable)
        return EFAULT;
    top -= modal.widgetTargetable;
    UiTarget modalTarget = target;
    modalTarget.widget = top;
    if (const int error = liveUiTarget(guiInstance, ui, modalTarget))
        return error;
    std::array<uintptr_t, 256> ancestors{};
    size_t depth = 0;
    bool admitted = false;
    uintptr_t current = target.widget;
    while (current) {
        if (depth == ancestors.size())
            return E2BIG;
        for (size_t index = 0; index < depth; ++index) {
            if (ancestors[index] == current)
                return ELOOP;
        }
        ancestors[depth++] = current;
        admitted |= current == top;
        if (!fm::addressRange(current, ui.widgetSize) || !fm::read(current + modal.parent, current))
            return EFAULT;
    }
    return admitted ? 0 : EACCES;
}

int selectUiActionTarget(uintptr_t guiInstance, void *gui, const FmLinuxUiLayout &ui,
                         const FmLinuxModalLayout &modal, const FmLinuxUiSelector &selector,
                         const uint32_t *cancel, FmLinuxUiSnapshot &snapshot, UiTarget &output) {
    output = {};
    UiTarget selected;
    if (const int error = selectUiTarget(gui, ui, selector, cancel, snapshot, selected))
        return error;
    if (const int error = checkUiModal(guiInstance, ui, modal, selected))
        return error;
    output = selected;
    return 0;
}
