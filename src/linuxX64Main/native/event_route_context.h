#pragma once
#include <stdint.h>

typedef struct FmLinuxEventReceiverLayout {
    uint64_t global;
    uint64_t guiInstance;
    uint64_t guiVtable;
    uint64_t guiTypeInfo;
    uint64_t handlerVtable;
    uint64_t handlerTypeInfo;
    uint32_t globalSize;
    uint32_t stateMember;
    uint32_t stateSize;
    uint32_t guiSize;
    uint32_t guiHandler;
    uint32_t handlerSize;
} FmLinuxEventReceiverLayout;

#ifdef __cplusplus
#include "event_route.h"
#include "input_task_context.h"

// A resolver for one admitted route. The owner must establish the game-owned execution phase and the input
// service's lifetime. Expected service addresses are comparison-only ownership tokens; all receivers are fresh.
// Do not use this resolver to release keys after a failed world binding: that needs the emitter's separate cleanup.
class EventRouteContext {
public:
    EventRouteContext(InputTaskContext &task, const FmLinuxEventReceiverLayout &layout,
                      uintptr_t expectedGlobal, uintptr_t expectedState);
    static int resolve(EventRouteStage stage, void *context, void *&receiver) noexcept;
    int failure() const { return failure_; }

private:
    int read(EventRouteStage stage, void *&receiver);
    InputTaskContext &task_;
    const FmLinuxEventReceiverLayout layout_;
    const uintptr_t expectedGlobal_;
    const uintptr_t expectedState_;
    int failure_ = 0;
};
#endif
