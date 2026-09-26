#include "resident_input.h"
#include "protocol.h"
#include <cstring>
#include <utility>

namespace {
std::vector<InputStep> readSteps(const Symbols &symbols, const FmInputTask &wire) {
    require(wire.count <= FM_MAX_INPUT_STEPS && wire.stopPrevious <= 1, "Invalid input task bounds");
    std::vector<InputStep> steps;
    steps.reserve(wire.count);
    constexpr FmSymbol mouseSymbols[]{MouseLeft, MouseRight, MouseMiddle, Mouse4, Mouse5};
    for (uint32_t index = 0; index < wire.count; ++index) {
        const auto &row = wire.operations[index];
        require(row.count <= FM_MAX_INPUT_BUTTONS && row.hasPosition <= 1, "Invalid input operation bounds");
        InputStep step{row.ticks, {}, {}, row.wheel};
        require(row.motionCount <= FM_MAX_INPUT_MOTION, "Mouse motion exceeds supported bound");
        for (uint32_t i = 0; i < row.motionCount; ++i)
            step.motion.push_back({row.motion[i].tick, {row.motion[i].x, row.motion[i].y}});
        if (row.hasPosition)
            step.position = InputPosition{row.x, row.y};
        for (uint32_t i = 0; i < row.count; ++i) {
            auto code = row.buttons[i].code;
            const auto device = static_cast<InputDevice>(row.buttons[i].device);
            if (device == InputDevice::Mouse) {
                require(code >= 1 && code <= 5, "Invalid logical mouse button");
                const auto address = symbols.address[mouseSymbols[code - 1]];
                require(address, "Mouse button adapter is unavailable");
                memcpy(&code, reinterpret_cast<const void *>(address + symbols.controls.mouseValue), sizeof(code));
            }
            step.buttons.push_back({device, code});
        }
        steps.push_back(std::move(step));
    }
    InputSequence::validate(steps);
    return steps;
}
} // namespace

struct ResidentInput::Task {
    HANDLE mapping{}, owner{};
    FmInputTask *wire{};
    std::unique_ptr<InputSequence> sequence;
    void *game{};
    uint64_t epoch{};

    ~Task() {
        if (wire)
            UnmapViewOfFile(wire);
        if (mapping)
            CloseHandle(mapping);
        if (owner)
            CloseHandle(owner);
    }
};

class ResidentInput::Emitter final : public InputEmitter {
    const Symbols &symbols;
    ResidentInput &runtime;
    InputEventFunctions functions;

    void checkWorld() {
        const auto context = gameInputContext(symbols);
        require(runtime.task && runtime.task->epoch == runtime.worldEpoch.load() && context.game == runtime.task->game,
                "Input world changed during dispatch");
    }

  public:
    Emitter(const Symbols &symbols, ResidentInput &runtime)
        : symbols(symbols), runtime(runtime), functions(gameInputFunctions(symbols)) {}

    void move(InputPosition position) override {
        checkWorld();
        GameInputEvents(symbols.timedInput.events, functions, gameInputState(symbols)).move(position);
        checkWorld();
    }

    void button(InputButton button, bool down) override {
        if (down)
            checkWorld();
        GameInputEvents(symbols.timedInput.events, functions, gameInputState(symbols)).button(button, down);
        if (down)
            checkWorld();
    }

    void wheel(int32_t direction) override {
        checkWorld();
        GameInputEvents(symbols.timedInput.events, functions, gameInputState(symbols)).wheel(direction);
        checkWorld();
    }
};

ResidentInput::ResidentInput() = default;
ResidentInput::~ResidentInput() = default;

bool ResidentInput::active() const {
    return bool(task);
}

void ResidentInput::cancel(const char *reason) {
    if (task)
        task->sequence->cancel(reason);
}

void ResidentInput::worldDestroyed(void *game) {
    // Destruction may be reentrant through an input/UI event. Never dispatch another event here.
    if (game == boundGame.load())
        worldEpoch.fetch_add(1);
}

void ResidentInput::pollCancellation() {
    if (!task)
        return;
    if (InterlockedCompareExchange(&task->wire->cancel, 0, 0))
        cancel("Input cancelled by caller");
    if (task->epoch != worldEpoch.load())
        cancel("Input world was unloaded");
    const auto state = WaitForSingleObject(task->owner, 0);
    if (state == WAIT_OBJECT_0)
        cancel("Input owner process exited");
    else if (state != WAIT_TIMEOUT)
        cancel("Cannot observe input owner process");
}

void ResidentInput::publish() {
    if (!task)
        return;
    const auto &sequence = *task->sequence;
    InterlockedExchange(&task->wire->completedOperations, static_cast<LONG>(sequence.completed()));
    InterlockedExchange64(&task->wire->evaluatedTicks, static_cast<LONG64>(sequence.ticks()));
    const auto state = sequence.state();
    if (state == InputSequenceState::Succeeded || state == InputSequenceState::Aborted) {
        strncpy_s(task->wire->reason, sequence.reason().c_str(), _TRUNCATE);
        InterlockedExchange(&task->wire->state, state == InputSequenceState::Succeeded ? 2 : 3);
        boundGame.store(nullptr);
        task.reset();
    }
}

void ResidentInput::frontend(const Symbols &symbols) {
    if (!task || evaluating)
        return;
    pollCancellation();
    try {
        const auto context = gameInputContext(symbols);
        if (context.game != task->game)
            cancel("Input world changed");
    } catch (const std::exception &error) {
        cancel(error.what());
    }
    Emitter emitter(symbols, *this);
    task->sequence->cleanup(emitter);
    publish();
}

void ResidentInput::admit(const Symbols &symbols, const char *name, size_t capacity) {
    const auto length = strnlen(name, capacity);
    require(length && length < capacity, "Invalid input mapping name");
    const auto prefix = "Local\\factorio-mcp-input-" + std::to_string(GetCurrentProcessId()) + "-";
    require(std::string(name, length).starts_with(prefix), "Input mapping belongs to another target");
    auto candidate = std::make_unique<Task>();
    candidate->mapping = OpenFileMappingA(FILE_MAP_ALL_ACCESS, FALSE, name);
    require(candidate->mapping, "Cannot open input task mapping");
    candidate->wire =
        static_cast<FmInputTask *>(MapViewOfFile(candidate->mapping, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(FmInputTask)));
    require(candidate->wire, "Cannot map input task");
    auto &wire = *candidate->wire;
    require(fm_input_state(&wire) == 0, "Input task is already admitted");
    candidate->sequence = std::make_unique<InputSequence>(readSteps(symbols, wire));
    candidate->owner = OpenProcess(SYNCHRONIZE, FALSE, wire.ownerPid);
    require(candidate->owner && WaitForSingleObject(candidate->owner, 0) == WAIT_TIMEOUT, "Input owner is absent");
    require(!fm_input_state(&wire) && !wire.cancel, "Input cancelled before admission");
    // Validate the complete replacement and its current context before touching the previous task.
    if (wire.count) {
        candidate->game = gameInputContext(symbols).game;
        candidate->epoch = worldEpoch.load();
    }
    if (task) {
        require(wire.stopPrevious, "Input is busy; set stop_previous to replace it");
        cancel("Input replaced by another call");
        frontend(symbols);
        require(!task, "Previous input is still releasing its buttons");
    }
    InterlockedExchange(&wire.state, 1);
    task = std::move(candidate);
    boundGame.store(task->game);
    publish();
}

void ResidentInput::evaluate(const Symbols &symbols, void *receiver, void (*original)(void *)) {
    if (!task || evaluating) {
        original(receiver);
        return;
    }
    GameInputContext context{};
    try {
        context = gameInputContext(symbols);
    } catch (const std::exception &error) {
        cancel(error.what());
        original(receiver);
        return;
    }
    if (receiver != context.source) {
        original(receiver);
        return;
    }

    struct Evaluating {
        bool &value;

        explicit Evaluating(bool &value) : value(value) {
            value = true;
        }

        ~Evaluating() {
            value = false;
        }
    } guard(evaluating);

    const auto epoch = worldEpoch.load();
    pollCancellation();
    Emitter emitter(symbols, *this);
    task->sequence->beforeTick(context.tick, emitter);
    // A dispatched menu action can unload the receiver reentrantly. Never call a destroyed source.
    if (worldEpoch.load() == epoch) {
        original(receiver);
        task->sequence->afterTick(context.tick);
    } else {
        cancel("Input world was unloaded during dispatch");
        task->sequence->cleanup(emitter);
    }
    publish();
}
