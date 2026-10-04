#include "input_events.h"
#include <cassert>
#include <cstddef>
#include <new>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr int keyDown = 71, keyUp = 92, mouseMove = 117, mouseDown = 123, mouseUp = 126, wheel = 131, mouseEnter = 149;

struct Event {
    double timestamp;
    int type;
    uint32_t key{};
    int32_t x{}, y{};
    uint32_t button{};
    int32_t dw{};
    int32_t dy{};
    std::string owned = std::string(100, 'x');
};

struct State {
    uint64_t prefix{};
    int32_t x{11}, y{29};
    bool inside{};
    std::set<uint32_t> held;
};

State *expectedState{};
enum class Failure { None, Construct, Update, Process, Post };
Failure failure{};
bool transition{};
unsigned constructed{}, destroyed{};
std::vector<std::string> trace;
std::vector<Event> events;

void fail(Failure stage) {
    if (failure == stage) {
        failure = Failure::None;
        throw std::runtime_error("Fixture event failure");
    }
}

void *construct(void *output, double timestamp, int type) {
    assert(timestamp == 123.456);
    trace.push_back("construct");
    fail(Failure::Construct);
    ++constructed;
    return new (output) Event{timestamp, type};
}

void *destroy(void *object, unsigned flags) {
    assert(flags == 0);
    auto *event = static_cast<Event *>(object);
    assert(event->owned.size() == 100);
    event->~Event();
    ++destroyed;
    trace.push_back("destroy");
    return object;
}

uint32_t ticks() {
    return 123456;
}

void update(void *object, const void *input) {
    assert(object == expectedState);
    auto &state = *static_cast<State *>(object);
    const auto &event = *static_cast<const Event *>(input);
    trace.push_back("update");
    if (event.type == mouseEnter) {
        state.inside = true;
    } else if (event.type == mouseMove) {
        state.x = event.x;
        state.y = event.y;
    } else if (event.type == keyDown || event.type == mouseDown) {
        state.held.insert(event.type == keyDown ? event.key : event.button);
    } else if (event.type == keyUp || event.type == mouseUp) {
        state.held.erase(event.type == keyUp ? event.key : event.button);
    }
    fail(Failure::Update);
}

int process(const void *input, bool fromQueue) {
    assert(!fromQueue);
    trace.push_back("process");
    events.push_back(*static_cast<const Event *>(input));
    fail(Failure::Process);
    const int result = transition ? 1 : 0;
    transition = false;
    return result;
}

void post(void *object, const void *) {
    assert(object == expectedState);
    trace.push_back("post");
    fail(Failure::Post);
}

const InputEventFunctions api{construct, destroy, ticks, update, process, post};
const InputEventLayout layout{sizeof(Event),
                              offsetof(Event, key),
                              offsetof(Event, x),
                              offsetof(Event, y),
                              offsetof(Event, button),
                              sizeof(State),
                              offsetof(State, x),
                              offsetof(State, y),
                              keyDown,
                              keyUp,
                              mouseMove,
                              mouseDown,
                              mouseUp,
                              offsetof(Event, dw),
                              offsetof(Event, dy),
                              wheel,
                              offsetof(State, inside),
                              mouseEnter};

void normalRoutingAndLiveMousePosition() {
    State state;
    expectedState = &state;
    InputEventButtons buttons;
    GameInputEvents emitter(layout, api, buttons, &state);
    trace.clear();
    events.clear();
    emitter.button({InputDevice::Keyboard, 83}, true);
    assert((trace == std::vector<std::string>{"construct", "update", "process", "post", "destroy"}));
    emitter.button({InputDevice::Keyboard, 83}, false);
    emitter.move({640, 360});
    assert(state.inside && events[2].type == mouseEnter && events[3].type == mouseMove);
    const auto entered = events.size();
    emitter.move({640, 360});
    assert(events.size() == entered + 1 && events.back().type == mouseMove);
    emitter.button({InputDevice::Mouse, 65}, true);
    assert(events.back().x == 640 && events.back().y == 360 && events.back().button == 65);
    state.x = 24;
    state.y = -10;
    emitter.button({InputDevice::Mouse, 65}, false);
    assert(events.back().type == mouseUp && events.back().x == 24 && events.back().y == -10);
    assert(state.held.empty() && constructed == destroyed);
    emitter.wheel(1);
    assert(events.back().type == wheel && events.back().dw == 1 && events.back().dy == 1);
    assert(events.back().x == 24 && events.back().y == -10);
    emitter.wheel(-1);
    assert(events.back().dw == -1 && events.back().dy == -1 && state.held.empty() && constructed == destroyed);
}

void mouseEntryRevalidatesBeforeTheFollowingMove() {
    State state;
    expectedState = &state;
    InputEventButtons buttons;
    GameInputEvents emitter(layout, api, buttons, &state);
    events.clear();
    unsigned checked = 0;
    bool rejected = false;
    try {
        emitter.move(
            {640, 360},
            [](void *owner) {
                ++*static_cast<unsigned *>(owner);
                assert(events.size() == 1 && events.back().type == mouseEnter);
                assert(constructed == destroyed);
                throw std::runtime_error("Fixture world was replaced during mouse entry");
            },
            &checked);
    } catch (const std::runtime_error &) {
        rejected = true;
    }
    assert(rejected && checked == 1 && events.size() == 1 && state.inside);
    assert(state.x == 11 && state.y == 29 && constructed == destroyed);
}

void failedDispatchRemainsReleasable() {
    for (const auto stage : {Failure::Construct, Failure::Update, Failure::Process, Failure::Post}) {
        State state;
        expectedState = &state;
        InputEventButtons buttons;
        GameInputEvents emitter(layout, api, buttons, &state);
        InputSequence task({{InputKind::Keyboard, 83, {{0, 9}}}});
        failure = stage;
        trace.clear();
        task.beforeTick(100, emitter);
        assert(task.state() == InputSequenceState::Aborted && task.ticks() == 0);
        assert(state.held.empty() && !task.hasHeldInput() && constructed == destroyed);
        if (stage == Failure::Process) {
            assert((std::vector<std::string>(trace.begin(), trace.begin() + 5) ==
                    std::vector<std::string>{"construct", "update", "process", "post", "destroy"}));
        }
    }
    State state;
    expectedState = &state;
    InputEventButtons buttons;
    GameInputEvents emitter(layout, api, buttons, &state);
    InputSequence task({{InputKind::MouseButton, 65, {{0, 9}}}});
    transition = true;
    task.beforeTick(100, emitter);
    assert(task.state() == InputSequenceState::Aborted && state.held.empty());
    assert(task.reason().find("application transition") != std::string::npos);
    assert(constructed == destroyed);
}

void widgetChordUpdatesStateWithoutRoutingAnotherAction() {
    State state;
    expectedState = &state;
    InputEventButtons buttons;
    GameInputEvents emitter(layout, api, buttons, &state, false);
    trace.clear();
    events.clear();
    emitter.button({InputDevice::Keyboard, 83}, true);
    emitter.button({InputDevice::Mouse, 65}, true);
    assert(state.held.contains(83) && state.held.contains(65));
    assert(events.empty());
    emitter.button({InputDevice::Mouse, 65}, false);
    emitter.button({InputDevice::Keyboard, 83}, false);
    assert(state.held.empty() && constructed == destroyed);
    for (const auto &entry : trace)
        assert(entry != "process");
}

void releaseProgressSurvivesPhaseAdaptersWithoutReplaying() {
    for (const auto stage : {Failure::Construct, Failure::Update, Failure::Process, Failure::Post}) {
        State state;
        expectedState = &state;
        InputEventButtons buttons;
        InputSequence task({{InputKind::Keyboard, 83, {{0, 9}}}, {InputKind::MouseButton, 65, {{0, 9}}}});
        GameInputEvents press(layout, api, buttons, &state);
        task.beforeTick(100, press);
        assert(state.held.size() == 2 && buttons.active());
        task.cancel("Fixture cancellation");
        failure = stage;
        GameInputEvents release(layout, api, buttons, &state);
        task.cleanup(release);
        assert(task.state() == InputSequenceState::Releasing && buttons.active());
        // A failed mouse release cannot keep the rest of the chord down.
        assert(!state.held.contains(83));
        const auto beforeRetry = constructed;
        GameInputEvents nextPhase(layout, api, buttons, &state);
        task.cleanup(nextPhase);
        if (stage == Failure::Construct) {
            assert(constructed == beforeRetry + 1 && task.state() == InputSequenceState::Aborted);
            assert(state.held.empty() && !buttons.active());
        } else {
            assert(constructed == beforeRetry && task.state() == InputSequenceState::Releasing);
            assert(buttons.active() && task.hasHeldInput());
            // Local state was cleared even when routing/post-update failed. It is not proof of completed cleanup.
            assert(state.held.empty());
        }
        assert(constructed == destroyed);
    }
}

void releaseNeverTargetsAReplacementInputState() {
    State state, replacement;
    expectedState = &state;
    InputEventButtons buttons;
    GameInputEvents first(layout, api, buttons, &state);
    first.button({InputDevice::Keyboard, 83}, true);
    const auto before = constructed;
    bool rejected = false;
    try {
        GameInputEvents other(layout, api, buttons, &replacement);
        other.button({InputDevice::Keyboard, 83}, false);
    } catch (const std::invalid_argument &) {
        rejected = true;
    }
    assert(rejected && constructed == before && buttons.active() && state.held.contains(83));
    first.button({InputDevice::Keyboard, 83}, false);
    assert(state.held.empty() && !buttons.active());
}

void invalidLayoutNeverConstructsAnEvent() {
    State state;
    const auto before = constructed;
    for (unsigned field = 0; field < 3; ++field) {
        auto invalid = layout;
        if (field == 0)
            invalid.eventSize = 257;
        if (field == 1)
            invalid.scancode = invalid.eventSize;
        if (field == 2)
            invalid.stateMouseX = invalid.stateSize;
        bool rejected = false;
        try {
            InputEventButtons buttons;
            GameInputEvents emitter(invalid, api, buttons, &state);
        } catch (const std::invalid_argument &) {
            rejected = true;
        }
        assert(rejected);
    }
    assert(constructed == before);
}

struct Map {
    bool paused{};
    uint8_t stop{};
    uint64_t tick{250};
};

struct Player {
    Map *map;
};

struct Source {
    Player *player;
};

struct Game {
    Map *map;
    Player *player;
};

struct GlobalState {
    Game *game;
    Source *source;
    State *input;
    bool loading{};
};

bool loading(void *global) {
    return static_cast<GlobalState *>(global)->loading;
}

void *game(void *global) {
    return static_cast<GlobalState *>(global)->game;
}

void *player(void *game) {
    return static_cast<Game *>(game)->player;
}

void onlyTheOwnedRunningWorldSuppliesAnInputClock() {
    Map map;
    Player owner{&map}, another{&map};
    Source source{&owner};
    Game world{&map, &owner};
    State input;
    GlobalState global{&world, &source, &input};
    GlobalState *globalPointer = &global;
    Symbols symbols{};
    symbols.world.supported = symbols.pauseSupported = symbols.timedInput.supported = 1;
    symbols.address[Global] = reinterpret_cast<uintptr_t>(&globalPointer);
    symbols.address[Loading] = reinterpret_cast<uintptr_t>(loading);
    symbols.address[GetGame] = reinterpret_cast<uintptr_t>(game);
    symbols.address[LocalPlayer] = reinterpret_cast<uintptr_t>(player);
    symbols.gameMapOffset = offsetof(Game, map);
    symbols.mapPausedOffset = offsetof(Map, paused);
    symbols.mapStopLevelOffset = offsetof(Map, stop);
    symbols.timedInput.globalInputState = offsetof(GlobalState, input);
    symbols.timedInput.globalSource = offsetof(GlobalState, source);
    symbols.timedInput.sourcePlayer = offsetof(Source, player);
    symbols.timedInput.playerMap = offsetof(Player, map);
    symbols.timedInput.mapTick = offsetof(Map, tick);
    const auto context = gameInputContext(symbols);
    assert(context.game == &world && context.source == &source && context.player == &owner && context.map == &map &&
           context.state == &input && context.tick == 250);
    auto rejected = [&] {
        try {
            gameInputContext(symbols);
            return false;
        } catch (const std::invalid_argument &) {
            return true;
        }
    };
    source.player = &another;
    assert(rejected());
    source.player = &owner;
    map.paused = true;
    assert(rejected());
    map.paused = false;
    map.stop = 1;
    assert(rejected());
    map.stop = 0;
    global.loading = true;
    assert(rejected());
    global.loading = false;
    world.map = nullptr;
    assert(rejected());
    world.map = &map;
    global.game = nullptr;
    assert(rejected());
    global.game = &world;
    symbols.pauseSupported = 0;
    assert(rejected());
}
} // namespace

int main() {
    normalRoutingAndLiveMousePosition();
    mouseEntryRevalidatesBeforeTheFollowingMove();
    failedDispatchRemainsReleasable();
    widgetChordUpdatesStateWithoutRoutingAnotherAction();
    releaseProgressSurvivesPhaseAdaptersWithoutReplaying();
    releaseNeverTargetsAReplacementInputState();
    invalidLayoutNeverConstructsAnEvent();
    onlyTheOwnedRunningWorldSuppliesAnInputClock();
}
