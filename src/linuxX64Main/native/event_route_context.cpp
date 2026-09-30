#include "event_route_context.h"
#include "memory_read.h"
#include <cerrno>

namespace {
bool object(uintptr_t address, uint32_t size) {
    return size >= 8 && size <= 64 * 1024 * 1024 && fm::addressRange(address, size) && address % 8 == 0;
}

bool member(uint32_t offset, uint32_t size) {
    return offset % 8 == 0 && fm::member(offset, 8, size);
}

bool table(uintptr_t address, uintptr_t type) {
    return address >= 16 && object(address, 8) && object(type, 16);
}

int typed(uintptr_t address, uint32_t size, uintptr_t table, uintptr_t type) {
    if (!address)
        return ENOENT;
    uintptr_t actual, adjustment, identity;
    if (!object(address, size) || !fm::read(address, actual))
        return EFAULT;
    if (actual != table)
        return ENOTSUP;
    if (!fm::read(table - 16, adjustment) || !fm::read(table - 8, identity))
        return EFAULT;
    return adjustment || identity != type ? ESTALE : 0;
}
} // namespace

EventRouteContext::EventRouteContext(InputTaskContext &task, const FmLinuxEventReceiverLayout &layout,
                                     uintptr_t expectedGlobal, uintptr_t expectedState)
    : task_(task), layout_(layout), expectedGlobal_(expectedGlobal), expectedState_(expectedState) {
    if (!object(layout.global, 8) || !object(layout.guiInstance, 8) ||
        !object(expectedGlobal, layout.globalSize) || !object(expectedState, layout.stateSize) ||
        !member(layout.stateMember, layout.globalSize) || !member(layout.guiHandler, layout.guiSize) ||
        layout.guiHandler < 8 || layout.guiSize < 8 || layout.guiSize > 64 * 1024 * 1024 ||
        layout.handlerSize < 8 || layout.handlerSize > 64 * 1024 * 1024 ||
        !table(layout.guiVtable, layout.guiTypeInfo) || !table(layout.handlerVtable, layout.handlerTypeInfo))
        failure_ = EINVAL;
}

int EventRouteContext::resolve(EventRouteStage stage, void *context, void *&receiver) noexcept {
    receiver = nullptr;
    if (!context)
        return EINVAL;
    auto &self = *static_cast<EventRouteContext *>(context);
    if (self.failure_)
        return self.failure_;
    try {
        self.failure_ = self.read(stage, receiver);
    } catch (...) {
        self.failure_ = EFAULT;
    }
    if (self.failure_)
        receiver = nullptr;
    return self.failure_;
}

int EventRouteContext::read(EventRouteStage stage, void *&receiver) {
    InputContext current;
    if (const int error = task_.read(current))
        return error;
    // Source and GUI callbacks also consult this service. Check it at every boundary, not only when
    // selecting it as the direct receiver, so a replacement cannot silently split an admitted event.
    uintptr_t global, state;
    if (!fm::read(layout_.global, global))
        return EFAULT;
    if (global != expectedGlobal_)
        return ESTALE;
    if (!fm::read(global + layout_.stateMember, state))
        return EFAULT;
    if (state != expectedState_)
        return ESTALE;
    uintptr_t selected = 0;
    switch (stage) {
    case EventRouteStage::Source:
    case EventRouteStage::Evaluation:
        selected = current.source;
        break;
    case EventRouteStage::Update:
    case EventRouteStage::PostUpdate:
        selected = state;
        break;
    case EventRouteStage::GuiEvent:
    case EventRouteStage::GuiLogic: {
        uintptr_t gui;
        if (!fm::read(layout_.guiInstance, gui))
            return EFAULT;
        if (const int error = typed(gui, layout_.guiSize, layout_.guiVtable, layout_.guiTypeInfo))
            return error;
        selected = gui;
        if (stage == EventRouteStage::GuiEvent) {
            if (!fm::read(gui + layout_.guiHandler, selected))
                return EFAULT;
            if (const int error = typed(selected, layout_.handlerSize, layout_.handlerVtable, layout_.handlerTypeInfo))
                return error;
        }
        break;
    }
    default:
        return EINVAL;
    }
    if (!selected)
        return ESTALE;
    receiver = reinterpret_cast<void *>(selected);
    return 0;
}
