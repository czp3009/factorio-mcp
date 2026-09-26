#include "resident_input.h"
#include <cassert>
#include <cstring>
#include <set>
#include <string>
#include <vector>

namespace {
struct Event {
    double time;
    int type;
    uint32_t key;
    int32_t x, y;
    uint32_t button;
    int32_t dw{};
    int32_t dy{};
};

struct State {
    int32_t x{}, y{};
    std::set<uint32_t> held;
} state;

struct Map {
    uint64_t tick{100};
    bool paused{};
    uint8_t stop{};
} map;

struct Player {
    Map *map;
} player{&map};

struct Source {
    Player *player;
} source{&player};

struct Game {
    Map *map;
    Player *player;
} game{&map, &player};

struct GlobalState {
    Game *game;
    Source *source;
    State *state;
    bool loading{};
} global{&game, &source, &state};

GlobalState *globalPointer = &global;
ResidentInput *runtime{};
bool unloadOnDown{};
std::vector<std::set<uint32_t>> samples;
unsigned mouseLeft = 77;

void *construct(void *out, double time, int type) {
    *static_cast<Event *>(out) = {time, type, 0, 0, 0, 0};
    return out;
}

void *destroy(void *event, unsigned flags) {
    assert(!flags);
    return event;
}

uint32_t time() {
    return 1000;
}

void update(void *object, const void *value) {
    assert(object == &state);
    const auto &event = *static_cast<const Event *>(value);
    if (event.type == 1 || event.type == 4)
        state.held.insert(event.type == 1 ? event.key : event.button);
    if (event.type == 2 || event.type == 5)
        state.held.erase(event.type == 2 ? event.key : event.button);
    if (event.type == 3) {
        state.x = event.x;
        state.y = event.y;
    }
}

int process(const void *value, bool queued) {
    assert(!queued);
    if (unloadOnDown && static_cast<const Event *>(value)->type == 1) {
        unloadOnDown = false;
        runtime->worldDestroyed(&game);
        global.game = nullptr;
    }
    return 0;
}

void post(void *object, const void *) {
    assert(object == &state);
}

bool loading(void *object) {
    return static_cast<GlobalState *>(object)->loading;
}

void *getGame(void *object) {
    return static_cast<GlobalState *>(object)->game;
}

void *getPlayer(void *object) {
    return static_cast<Game *>(object)->player;
}

void original(void *object) {
    assert(object == &source);
    samples.push_back(state.held);
}

Symbols symbols() {
    Symbols result{};
    result.world.supported = result.pauseSupported = result.timedInput.supported = 1;
    result.address[Global] = reinterpret_cast<uintptr_t>(&globalPointer);
    result.address[Loading] = reinterpret_cast<uintptr_t>(loading);
    result.address[GetGame] = reinterpret_cast<uintptr_t>(getGame);
    result.address[LocalPlayer] = reinterpret_cast<uintptr_t>(getPlayer);
    result.address[EventConstructor] = reinterpret_cast<uintptr_t>(construct);
    result.address[EventDestructor] = reinterpret_cast<uintptr_t>(destroy);
    result.address[SdlTicks] = reinterpret_cast<uintptr_t>(time);
    result.address[InputStateUpdate] = reinterpret_cast<uintptr_t>(update);
    result.address[ProcessInputEvent] = reinterpret_cast<uintptr_t>(process);
    result.address[InputStatePostUpdate] = reinterpret_cast<uintptr_t>(post);
    result.address[MouseLeft] = reinterpret_cast<uintptr_t>(&mouseLeft);
    result.gameMapOffset = offsetof(Game, map);
    result.mapPausedOffset = offsetof(Map, paused);
    result.mapStopLevelOffset = offsetof(Map, stop);
    auto &layout = result.timedInput;
    layout.globalSource = offsetof(GlobalState, source);
    layout.globalInputState = offsetof(GlobalState, state);
    layout.sourcePlayer = offsetof(Source, player);
    layout.playerMap = offsetof(Player, map);
    layout.mapTick = offsetof(Map, tick);
    layout.events = {sizeof(Event),
                     offsetof(Event, key),
                     offsetof(Event, x),
                     offsetof(Event, y),
                     offsetof(Event, button),
                     sizeof(State),
                     offsetof(State, x),
                     offsetof(State, y),
                     1,
                     2,
                     3,
                     4,
                     5,
                     offsetof(Event, dw),
                     offsetof(Event, dy),
                     6};
    return result;
}

struct Mapping {
    HANDLE mapping;
    FmInputTask *wire;
    std::string name;

    Mapping() {
        static unsigned id{};
        name =
            "Local\\factorio-mcp-input-" + std::to_string(GetCurrentProcessId()) + "-fixture-" + std::to_string(++id);
        mapping =
            CreateFileMappingA(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, sizeof(FmInputTask), name.c_str());
        assert(mapping && GetLastError() != ERROR_ALREADY_EXISTS);
        wire = static_cast<FmInputTask *>(MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(FmInputTask)));
        assert(wire);
        wire->ownerPid = GetCurrentProcessId();
    }

    ~Mapping() {
        UnmapViewOfFile(wire);
        CloseHandle(mapping);
    }

    void step(unsigned ticks, std::initializer_list<uint32_t> keys) {
        auto &row = wire->operations[wire->count++];
        row.ticks = ticks;
        for (auto key : keys)
            row.buttons[row.count++] = {0, key};
    }

    void admit(ResidentInput &target, const Symbols &api) {
        target.admit(api, name.c_str(), name.size() + 1);
    }
};

template <class F> void rejected(F action) {
    bool failed{};
    try {
        action();
    } catch (const std::exception &) {
        failed = true;
    }
    assert(failed);
}

void runTick(ResidentInput &target, const Symbols &api) {
    target.evaluate(api, &source, original);
    ++map.tick;
}

void exactStepsAndIndependentResults() {
    ResidentInput target;
    auto api = symbols();
    Mapping task;
    task.step(2, {10});
    task.step(3, {20});
    task.step(1, {});
    task.step(1, {10, 20});
    task.admit(target, api);
    samples.clear();
    for (unsigned i = 0; i < 8; ++i)
        runTick(target, api);
    assert((samples == std::vector<std::set<uint32_t>>{{10}, {10}, {20}, {20}, {20}, {}, {10, 20}, {}}));
    assert(fm_input_state(task.wire) == 2 && fm_input_ticks(task.wire) == 7 && fm_input_completed(task.wire) == 4);
    assert(!target.active() && state.held.empty());
    Mapping next;
    next.step(1, {30});
    next.admit(target, api);
    runTick(target, api);
    runTick(target, api);
    assert(fm_input_state(task.wire) == 2 && fm_input_ticks(task.wire) == 7);
    assert(fm_input_state(next.wire) == 2 && state.held.empty());
}

void motionWireRunsWhileHeldAndRejectsInvalidReplacement() {
    ResidentInput target;
    const auto api = symbols();
    Mapping task;
    task.step(4, {10});
    auto &row = task.wire->operations[0];
    row.motionCount = 2;
    row.motion[0] = {2, 32, 48};
    row.motion[1] = {4, 64, 96};
    task.admit(target, api);
    runTick(target, api);
    Mapping invalid;
    invalid.step(4, {20});
    invalid.wire->stopPrevious = 1;
    invalid.wire->operations[0].motionCount = FM_MAX_INPUT_MOTION + 1;
    rejected([&] { invalid.admit(target, api); });
    assert(state.held.contains(10) && fm_input_state(task.wire) == 1);
    runTick(target, api);
    assert(state.x == 32 && state.y == 48 && state.held.contains(10));
    runTick(target, api);
    assert(state.x == 32 && state.y == 48 && state.held.contains(10));
    runTick(target, api);
    assert(state.x == 64 && state.y == 96 && state.held.contains(10));
    runTick(target, api);
    assert(state.held.empty() && fm_input_state(task.wire) == 2 && fm_input_ticks(task.wire) == 4);
}

void replacementValidatesBeforeCancellation() {
    ResidentInput target;
    const auto api = symbols();
    Mapping first;
    first.step(1000, {10});
    first.admit(target, api);
    runTick(target, api);
    Mapping second;
    second.step(1, {20});
    rejected([&] { second.admit(target, api); });
    assert(fm_input_state(first.wire) == 1 && state.held.contains(10));
    second.wire->stopPrevious = 1;
    second.wire->operations[0].ticks = 0;
    rejected([&] { second.admit(target, api); });
    assert(fm_input_state(first.wire) == 1 && state.held.contains(10));
    second.wire->operations[0].ticks = 1;
    second.admit(target, api);
    assert(fm_input_state(first.wire) == 3 && fm_input_ticks(first.wire) == 1 && state.held.empty());
    runTick(target, api);
    runTick(target, api);
    assert(fm_input_state(second.wire) == 2 && state.held.empty());
}

void pausedAndUnloadedWorldsReleaseWithoutTicks() {
    const auto api = symbols();
    for (unsigned mode = 0; mode < 3; ++mode) {
        ResidentInput target;
        Mapping task;
        task.step(1000, {10});
        task.admit(target, api);
        runTick(target, api);
        if (mode == 0)
            map.paused = true;
        if (mode == 1) {
            target.worldDestroyed(&game);
            global.game = nullptr;
        }
        if (mode == 2)
            fm_input_cancel(task.wire);
        target.frontend(api);
        assert(fm_input_state(task.wire) == 3 && fm_input_ticks(task.wire) == 1 && state.held.empty());
        map.paused = false;
        global.game = &game;
        runTick(target, api);
        assert(state.held.empty() && !target.active());
    }
}

void reentrantUnloadNeverCallsDestroyedReceiver() {
    ResidentInput target;
    runtime = &target;
    const auto api = symbols();
    Mapping task;
    task.step(2, {10});
    task.admit(target, api);
    samples.clear();
    unloadOnDown = true;
    target.evaluate(api, &source, original);
    assert(samples.empty() && state.held.empty());
    assert(fm_input_state(task.wire) == 3 && fm_input_ticks(task.wire) == 0);
    global.game = &game;
    runtime = nullptr;
}

void mouseAndStopOnly() {
    ResidentInput target;
    const auto api = symbols();
    Mapping mouse;
    mouse.step(1000, {});
    auto &row = mouse.wire->operations[0];
    row.count = 1;
    row.buttons[0] = {1, 1};
    row.hasPosition = 1;
    row.x = 41;
    row.y = 52;
    mouse.admit(target, api);
    runTick(target, api);
    assert(state.x == 41 && state.y == 52 && state.held.contains(mouseLeft));
    Mapping stop;
    stop.wire->stopPrevious = 1;
    stop.admit(target, api);
    assert(fm_input_state(mouse.wire) == 3 && fm_input_state(stop.wire) == 2 && state.held.empty());
}

void unrelatedWorldDestructionDoesNotCancel() {
    ResidentInput target;
    const auto api = symbols();
    Mapping task;
    task.step(2, {10});
    task.admit(target, api);
    runTick(target, api);
    Game unrelated{&map, &player};
    target.worldDestroyed(&unrelated);
    target.frontend(api);
    assert(fm_input_state(task.wire) == 1 && state.held.contains(10));
    runTick(target, api);
    runTick(target, api);
    assert(fm_input_state(task.wire) == 2 && state.held.empty());
}

void exitedOwnerReleasesWithoutAnotherInputTick() {
    wchar_t system[MAX_PATH]{};
    assert(GetSystemDirectoryW(system, MAX_PATH));
    const std::wstring executable = std::wstring(system) + L"\\cmd.exe";
    std::wstring command = L"\"" + executable + L"\" /D /Q /C exit 0";
    STARTUPINFOW startup{};
    startup.cb = sizeof(startup);
    PROCESS_INFORMATION child{};
    assert(CreateProcessW(executable.c_str(), command.data(), nullptr, nullptr, FALSE,
                          CREATE_SUSPENDED | CREATE_NO_WINDOW, nullptr, nullptr, &startup, &child));
    ResidentInput target;
    const auto api = symbols();
    Mapping task;
    task.wire->ownerPid = child.dwProcessId;
    task.step(1000, {10});
    task.admit(target, api);
    runTick(target, api);
    assert(state.held.contains(10));
    assert(ResumeThread(child.hThread) != DWORD(-1));
    assert(WaitForSingleObject(child.hProcess, 5000) == WAIT_OBJECT_0);
    target.frontend(api);
    assert(fm_input_state(task.wire) == 3 && state.held.empty() && !target.active());
    assert(strstr(task.wire->reason, "owner process exited"));
    CloseHandle(child.hThread);
    CloseHandle(child.hProcess);
}
} // namespace

int main() {
    motionWireRunsWhileHeldAndRejectsInvalidReplacement();
    exactStepsAndIndependentResults();
    replacementValidatesBeforeCancellation();
    pausedAndUnloadedWorldsReleaseWithoutTicks();
    reentrantUnloadNeverCallsDestroyedReceiver();
    mouseAndStopOnly();
    unrelatedWorldDestructionDoesNotCancel();
    exitedOwnerReleasesWithoutAnotherInputTick();
}
