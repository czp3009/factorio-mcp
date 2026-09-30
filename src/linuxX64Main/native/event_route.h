#pragma once
#include <cstdint>
#include <sys/types.h>

enum class EventRouteStage : uint8_t { Source = 1, GuiEvent = 2, GuiLogic = 4, Evaluation = 8, Update = 16, PostUpdate = 32 };
enum class EventUpdateOrder : uint8_t { BeforeSource, AfterEvaluation };

struct EventRouteProgress {
    uint8_t entered = 0;
    uint8_t returned = 0;
    uint8_t sourceResult = 0;
};

struct EventRouteFunctions {
    uint8_t (*source)(void *, const void *);
    void (*guiEvent)(void *, const void *);
    void (*guiLogic)(void *, bool);
    void (*evaluate)(void *);
    void (*update)(void *, const void *);
    void (*postUpdate)(void *, const void *);
};

// One synchronous attempt at the verified source/GUI/evaluation route. The owner must prove these entry ABIs,
// the non-owning Event payload, state-update order and calling thread/phase before admission. Key-release
// ownership belongs to the surrounding emitter. No borrowed receiver is stored.
class EventRoute {
public:
    // Reacquire a current receiver for this stage and validate the admitted lifetime binding. Called before and
    // after each native call. A successful pointer comparison alone is not a lifetime proof. Never throw here.
    using Resolve = int (*)(EventRouteStage, void *, void *&) noexcept;

    EventRoute(pid_t thread, const EventRouteFunctions &functions, EventUpdateOrder order, Resolve resolve, void *context);
    EventRoute(const EventRoute &) = delete;
    EventRoute &operator=(const EventRoute &) = delete;
    // Only the established thread accesses progress. Once attempted, dispatch never replays any stage.
    int dispatch(const void *event, EventRouteProgress &progress);

private:
    const pid_t thread_;
    const EventRouteFunctions functions_;
    const EventUpdateOrder order_;
    const Resolve resolve_;
    void *const context_;
    bool started_ = false;
};
