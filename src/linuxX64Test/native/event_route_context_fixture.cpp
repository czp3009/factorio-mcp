#include "event_route_context.h"
#include <cassert>
#include <cerrno>
#include <cstring>
#include <vector>
#include <sys/syscall.h>
#include <unistd.h>

namespace {
struct Handler { virtual ~Handler() = default; };
struct Gui {
    virtual ~Gui() = default;
    Handler *handler = nullptr;
};
struct Global {
    uint64_t padding = 0;
    void *state = nullptr;
};
InputContext world{0x1000, 0x2000, 0x3000, 0x4000, 0x5000, 1, 0, false};
int readContext(const FmLinuxInputContextConfig &, const uint32_t *, InputContext &output) {
    output = world;
    return 0;
}
void identity(void *object, uint64_t &table, uint64_t &type) {
    std::memcpy(&table, object, 8);
    std::memcpy(&type, reinterpret_cast<void *>(table - 8), 8);
}
uint32_t offset(const void *object, const void *member) {
    return static_cast<const char *>(member) - static_cast<const char *>(object);
}
struct Fixture {
    uint64_t state[4]{}, otherState[4]{};
    Global global{0, state};
    Global *root = &global;
    Handler handler, otherHandler;
    Gui gui, otherGui;
    Gui *guiRoot = &gui;
    ViewLifetime lifetime;
    InputTaskContext task{lifetime, readContext};
    FmLinuxEventReceiverLayout layout{};
    std::vector<EventRouteStage> calls;
    EventRouteStage retire = static_cast<EventRouteStage>(0);
    EventRouteStage replaceState = static_cast<EventRouteStage>(0);
    bool replaceGui = false;
    uint8_t consumed = 0;

    Fixture() {
        gui.handler = &handler;
        otherGui.handler = &otherHandler;
        layout.global = reinterpret_cast<uintptr_t>(&root);
        layout.globalSize = sizeof(global);
        layout.stateMember = offset(&global, &global.state);
        layout.stateSize = sizeof(state);
        layout.guiInstance = reinterpret_cast<uintptr_t>(&guiRoot);
        layout.guiSize = sizeof(gui);
        layout.guiHandler = offset(&gui, &gui.handler);
        layout.handlerSize = sizeof(handler);
        identity(&gui, layout.guiVtable, layout.guiTypeInfo);
        identity(&handler, layout.handlerVtable, layout.handlerTypeInfo);
        uint32_t cancel = 0;
        InputContext selected;
        assert(task.bind({}, &cancel, selected) == 0);
    }

    ~Fixture() { task.release(); }

    void call(EventRouteStage stage, void *receiver) {
        switch (stage) {
        case EventRouteStage::Source:
        case EventRouteStage::Evaluation:
            assert(receiver == reinterpret_cast<void *>(world.source));
            break;
        case EventRouteStage::GuiEvent:
            assert(receiver == guiRoot->handler);
            break;
        case EventRouteStage::GuiLogic:
            assert(receiver == guiRoot);
            break;
        default:
            assert(receiver == state);
        }
        calls.push_back(stage);
        if (stage == retire)
            lifetime.retired(world.view);
        if (replaceGui && stage == EventRouteStage::GuiEvent)
            guiRoot = &otherGui;
        if (stage == replaceState)
            global.state = otherState;
    }
};
Fixture *active;
uint8_t source(void *receiver, const void *event) {
    assert(event == active);
    active->call(EventRouteStage::Source, receiver);
    return active->consumed;
}
void guiEvent(void *receiver, const void *event) {
    assert(event == active);
    active->call(EventRouteStage::GuiEvent, receiver);
}
void logic(void *receiver, bool flag) {
    assert(!flag);
    active->call(EventRouteStage::GuiLogic, receiver);
}
void evaluate(void *receiver) { active->call(EventRouteStage::Evaluation, receiver); }
void update(void *receiver, const void *event) {
    assert(event == active);
    active->call(EventRouteStage::Update, receiver);
}
void postUpdate(void *receiver, const void *event) {
    assert(event == active);
    active->call(EventRouteStage::PostUpdate, receiver);
}
int dispatch(Fixture &fixture, EventUpdateOrder order, EventRouteProgress &progress) {
    active = &fixture;
    EventRouteContext context(fixture.task, fixture.layout, reinterpret_cast<uintptr_t>(&fixture.global),
        reinterpret_cast<uintptr_t>(fixture.state));
    EventRoute route(static_cast<pid_t>(syscall(SYS_gettid)),
        {source, guiEvent, logic, evaluate, update, postUpdate}, order, EventRouteContext::resolve, &context);
    const int error = route.dispatch(&fixture, progress);
    assert(context.failure() == error);
    const auto count = fixture.calls.size();
    assert(route.dispatch(&fixture, progress) == EALREADY && fixture.calls.size() == count);
    return error;
}
} // namespace

int main() {
    for (const auto order : {EventUpdateOrder::BeforeSource, EventUpdateOrder::AfterEvaluation}) {
        for (const uint8_t consumed : {0, 1}) {
            Fixture fixture;
            fixture.consumed = consumed;
            fixture.replaceGui = true;
            EventRouteProgress progress;
            assert(dispatch(fixture, order, progress) == 0);
            assert(progress.entered == (consumed ? 57 : 63) && progress.returned == progress.entered);
            assert(fixture.guiRoot == (consumed ? &fixture.gui : &fixture.otherGui));
        }
        for (const auto stage : {EventRouteStage::Source, EventRouteStage::GuiEvent, EventRouteStage::GuiLogic,
            EventRouteStage::Evaluation, EventRouteStage::Update, EventRouteStage::PostUpdate}) {
            for (const bool service : {false, true}) {
                Fixture fixture;
                if (service)
                    fixture.replaceState = stage;
                else
                    fixture.retire = stage;
                EventRouteProgress progress;
                assert(dispatch(fixture, order, progress) == ESTALE);
                assert(fixture.calls.back() == stage && fixture.task.owned());
                assert(progress.entered == progress.returned);
            }
        }
    }
    {
        Fixture fixture;
        fixture.replaceState = EventRouteStage::Update;
        EventRouteProgress progress;
        assert(dispatch(fixture, EventUpdateOrder::BeforeSource, progress) == ESTALE);
        assert(fixture.calls.size() == 1 && progress.entered == 16 && progress.returned == 16);
    }
    {
        Fixture fixture;
        fixture.gui.handler = reinterpret_cast<Handler *>(&fixture.gui);
        EventRouteProgress progress;
        assert(dispatch(fixture, EventUpdateOrder::BeforeSource, progress) == ENOTSUP);
        assert(fixture.calls.size() == 2 && progress.entered == 17);
    }
    {
        Fixture fixture;
        fixture.layout.guiHandler = 0;
        EventRouteProgress progress;
        assert(dispatch(fixture, EventUpdateOrder::BeforeSource, progress) == EINVAL);
        assert(fixture.calls.empty() && !progress.entered);
    }
}
