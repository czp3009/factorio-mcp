#include "event_route.h"
#include <cerrno>
#include <sys/syscall.h>
#include <unistd.h>

EventRoute::EventRoute(pid_t thread, const EventRouteFunctions &functions, EventUpdateOrder order, Resolve resolve, void *context)
    : thread_(thread), functions_(functions), order_(order), resolve_(resolve), context_(context) {}

int EventRoute::dispatch(const void *event, EventRouteProgress &progress) {
    if (thread_ <= 0 || syscall(SYS_gettid) != thread_)
        return EPERM;
    if (started_)
        return EALREADY;
    started_ = true;
    progress = {};
    if (!event || !resolve_ || !functions_.source || !functions_.guiEvent || !functions_.guiLogic || !functions_.evaluate ||
        !functions_.update || !functions_.postUpdate ||
        (order_ != EventUpdateOrder::BeforeSource && order_ != EventUpdateOrder::AfterEvaluation))
        return EINVAL;
    auto stage = [&](EventRouteStage selected, auto invoke) {
        void *receiver = nullptr;
        if (const int error = resolve_(selected, context_, receiver))
            return error;
        if (!receiver)
            return ESTALE;
        const auto bit = static_cast<uint8_t>(selected);
        progress.entered |= bit;
        try {
            invoke(receiver);
        } catch (...) {
            // Entry may already have applied a mutation. Retain progress, and never retry this route.
            return EFAULT;
        }
        progress.returned |= bit;
        receiver = nullptr;
        if (const int error = resolve_(selected, context_, receiver))
            return error;
        return receiver ? 0 : ESTALE;
    };
    auto update = [&] {
        return stage(EventRouteStage::Update, [&](void *receiver) { functions_.update(receiver, event); });
    };
    if (order_ == EventUpdateOrder::BeforeSource) {
        if (const int error = update())
            return error;
    }
    int error = stage(EventRouteStage::Source, [&](void *receiver) {
        progress.sourceResult = functions_.source(receiver, event);
    });
    if (error)
        return error;
    if (!progress.sourceResult) {
        error = stage(EventRouteStage::GuiEvent, [&](void *receiver) { functions_.guiEvent(receiver, event); });
        if (error)
            return error;
        error = stage(EventRouteStage::GuiLogic, [&](void *receiver) { functions_.guiLogic(receiver, false); });
        if (error)
            return error;
    }
    error = stage(EventRouteStage::Evaluation, [&](void *receiver) { functions_.evaluate(receiver); });
    if (error)
        return error;
    if (order_ == EventUpdateOrder::AfterEvaluation) {
        if (const int error = update())
            return error;
    }
    return stage(EventRouteStage::PostUpdate, [&](void *receiver) { functions_.postUpdate(receiver, event); });
}
