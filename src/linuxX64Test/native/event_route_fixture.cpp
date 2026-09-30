#include "event_route.h"
#include <algorithm>
#include <cassert>
#include <cerrno>
#include <stdexcept>
#include <thread>
#include <vector>
#include <sys/syscall.h>
#include <unistd.h>

namespace {
struct Fixture {
    uint8_t result = 0;
    EventRouteStage fail = static_cast<EventRouteStage>(0);
    bool throws = false, retired = false, nullReceiver = false, reenter = false;
    unsigned reads = 0;
    std::vector<EventRouteStage> calls;
    EventRoute *route = nullptr;
    EventRouteProgress *progress = nullptr;
    const void *event = nullptr;

    void call(EventRouteStage stage) {
        assert(!retired);
        calls.push_back(stage);
        if (reenter) {
            const auto entered = progress->entered;
            assert(route->dispatch(event, *progress) == EALREADY);
            assert(progress->entered == entered);
        }
        if (stage == fail) {
            if (throws)
                throw std::runtime_error("native event callback failed");
            retired = true;
        }
    }
};

int resolve(EventRouteStage, void *context, void *&receiver) noexcept {
    auto &fixture = *static_cast<Fixture *>(context);
    ++fixture.reads;
    receiver = fixture.nullReceiver ? nullptr : context;
    return fixture.retired ? ESTALE : 0;
}

uint8_t source(void *receiver, const void *event) {
    auto &f = *static_cast<Fixture *>(receiver);
    assert(event == f.event);
    f.call(EventRouteStage::Source);
    return f.result;
}

void guiEvent(void *receiver, const void *event) {
    auto &f = *static_cast<Fixture *>(receiver);
    assert(event == f.event);
    f.call(EventRouteStage::GuiEvent);
}

void logic(void *receiver, bool value) {
    assert(!value);
    static_cast<Fixture *>(receiver)->call(EventRouteStage::GuiLogic);
}

void evaluate(void *receiver) {
    static_cast<Fixture *>(receiver)->call(EventRouteStage::Evaluation);
}

void update(void *receiver, const void *event) {
    auto &f = *static_cast<Fixture *>(receiver);
    assert(event == f.event);
    f.call(EventRouteStage::Update);
}

void postUpdate(void *receiver, const void *event) {
    auto &f = *static_cast<Fixture *>(receiver);
    assert(event == f.event);
    f.call(EventRouteStage::PostUpdate);
}

const EventRouteFunctions functions{source, guiEvent, logic, evaluate, update, postUpdate};

void runOrder(EventUpdateOrder order) {
    std::vector<EventRouteStage> all{EventRouteStage::Source, EventRouteStage::GuiEvent,
        EventRouteStage::GuiLogic, EventRouteStage::Evaluation};
    if (order == EventUpdateOrder::BeforeSource)
        all.insert(all.begin(), EventRouteStage::Update);
    else
        all.push_back(EventRouteStage::Update);
    all.push_back(EventRouteStage::PostUpdate);
    for (const uint8_t result : {0, 1, 7}) {
        Fixture f;
        f.result = result;
        f.reenter = true;
        f.event = &f;
        EventRoute route(static_cast<pid_t>(syscall(SYS_gettid)), functions, order, resolve, &f);
        EventRouteProgress progress;
        f.route = &route;
        f.progress = &progress;
        assert(route.dispatch(f.event, progress) == 0);
        auto expected = all;
        if (result) {
            expected.erase(std::remove(expected.begin(), expected.end(), EventRouteStage::GuiEvent), expected.end());
            expected.erase(std::remove(expected.begin(), expected.end(), EventRouteStage::GuiLogic), expected.end());
        }
        assert(f.calls == expected);
        assert(progress.entered == (result ? 57 : 63) && progress.returned == progress.entered);
        assert(progress.sourceResult == result && f.reads == f.calls.size() * 2);
        const auto count = f.calls.size();
        assert(route.dispatch(f.event, progress) == EALREADY && f.calls.size() == count);
    }
    for (const bool throws : {false, true}) for (const auto fail : all) {
        Fixture f;
        f.event = &f;
        f.fail = fail;
        f.throws = throws;
        EventRoute route(static_cast<pid_t>(syscall(SYS_gettid)), functions, order, resolve, &f);
        EventRouteProgress progress;
        assert(route.dispatch(f.event, progress) == (throws ? EFAULT : ESTALE));
        assert(f.calls.back() == fail);
        uint8_t entered = 0;
        for (const auto stage : all) {
            entered |= static_cast<uint8_t>(stage);
            if (stage == fail)
                break;
        }
        assert(progress.entered == entered);
        assert(progress.returned == (throws ? entered & ~static_cast<uint8_t>(fail) : entered));
        const auto count = f.calls.size();
        assert(route.dispatch(f.event, progress) == EALREADY && f.calls.size() == count);
    }
    for (const bool missing : {false, true}) {
        Fixture f;
        f.event = &f;
        f.nullReceiver = missing;
        f.retired = !missing;
        EventRoute route(static_cast<pid_t>(syscall(SYS_gettid)), functions, order, resolve, &f);
        EventRouteProgress progress;
        assert(route.dispatch(f.event, progress) == ESTALE);
        assert(progress.entered == 0 && f.calls.empty());
    }
}

void run() {
    runOrder(EventUpdateOrder::BeforeSource);
    runOrder(EventUpdateOrder::AfterEvaluation);
    Fixture f;
    f.event = &f;
    EventRoute route(static_cast<pid_t>(syscall(SYS_gettid)), functions,
        static_cast<EventUpdateOrder>(255), resolve, &f);
    EventRouteProgress progress;
    assert(route.dispatch(f.event, progress) == EINVAL && f.calls.empty() && !f.reads);
}
} // namespace

int main() {
    run();
    std::thread worker(run);
    worker.join();
    Fixture f;
    f.event = &f;
    EventRoute route(getpid(), functions, EventUpdateOrder::BeforeSource, resolve, &f);
    EventRouteProgress progress{91, 92, 93};
    std::thread foreign([&] { assert(route.dispatch(f.event, progress) == EPERM); });
    foreign.join();
    assert(progress.entered == 91 && progress.returned == 92 && progress.sourceResult == 93 && !f.reads);
    assert(route.dispatch(f.event, progress) == 0);
}
