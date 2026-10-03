#include "input_events.h"
#include <algorithm>
#include <array>
#include <cstring>
#include <exception>
#include <stdexcept>

namespace {
template <class T> T read(const void *object, unsigned offset) {
    T result;
    memcpy(&result, static_cast<const unsigned char *>(object) + offset, sizeof(result));
    return result;
}

template <class T> void write(void *object, unsigned offset, T value) {
    memcpy(static_cast<unsigned char *>(object) + offset, &value, sizeof(value));
}

void require(bool condition, const char *message) {
    if (!condition)
        throw std::invalid_argument(message);
}
} // namespace

InputEventFunctions gameInputFunctions(const Symbols &symbols) {
    return {reinterpret_cast<void *(*)(void *, double, int)>(symbols.address[EventConstructor]),
            reinterpret_cast<void *(*)(void *, unsigned)>(symbols.address[EventDestructor]),
            reinterpret_cast<uint32_t (*)()>(symbols.address[SdlTicks]),
            reinterpret_cast<void (*)(void *, const void *)>(symbols.address[InputStateUpdate]),
            reinterpret_cast<int (*)(const void *, bool)>(symbols.address[ProcessInputEvent]),
            reinterpret_cast<void (*)(void *, const void *)>(symbols.address[InputStatePostUpdate])};
}

GameInputContext gameInputContext(const Symbols &symbols) {
    require(symbols.timedInput.supported && symbols.world.supported && symbols.pauseSupported,
            "Timed input requires verified world, pause and event adapters");
    void *global = *reinterpret_cast<void **>(symbols.address[Global]);
    require(global && !reinterpret_cast<bool (*)(void *)>(symbols.address[Loading])(global),
            "Input requires an initialized, non-loading client");
    void *game = reinterpret_cast<void *(*)(void *)>(symbols.address[GetGame])(global);
    require(game, "Input requires a loaded world");
    const auto &layout = symbols.timedInput;
    void *source = read<void *>(global, layout.globalSource);
    void *state = read<void *>(global, layout.globalInputState);
    void *player = reinterpret_cast<void *(*)(void *)>(symbols.address[LocalPlayer])(game);
    require(source && state && player && read<void *>(source, layout.sourcePlayer) == player,
            "Input source does not belong to the injected client's local player");
    void *map = read<void *>(player, layout.playerMap);
    require(map && map == read<void *>(game, symbols.gameMapOffset), "Input source belongs to another world");
    require(!read<bool>(map, symbols.mapPausedOffset) && !read<uint8_t>(map, symbols.mapStopLevelOffset),
            "Input requires a normally running world");
    return {game, source, player, map, state, read<uint64_t>(map, layout.mapTick)};
}

void *gameInputState(const Symbols &symbols) {
    require(symbols.timedInput.supported, "Input event adapter is unavailable");
    void *global = *reinterpret_cast<void **>(symbols.address[Global]);
    require(global, "Global input owner is unavailable");
    void *state = read<void *>(global, symbols.timedInput.globalInputState);
    require(state, "Global input state is unavailable");
    return state;
}

GameInputEvents::GameInputEvents(const InputEventLayout &values, const InputEventFunctions &api,
                                 InputEventButtons &ownedButtons, void *receiver, bool routeEvents)
    : layout(values), functions(api), buttons(ownedButtons), state(receiver), route(routeEvents) {
    require(state && api.construct && api.destroy && api.ticks && api.update && api.process && api.postUpdate,
            "Game input event adapter is unavailable");
    require(layout.eventSize >= 4 && layout.eventSize <= 256 && layout.stateSize >= 4,
            "Unsupported game input event storage");
    for (const auto offset :
         {layout.scancode, layout.mouseX, layout.mouseY, layout.mouseButton, layout.mouseWheel, layout.mouseWheelY})
        require(offset <= layout.eventSize - 4, "Input event field exceeds its validated storage");
    require(layout.stateMouseX <= layout.stateSize - 4 && layout.stateMouseY <= layout.stateSize - 4,
            "Input cursor field exceeds its validated receiver");
    require(layout.stateMouseInWindow < layout.stateSize, "Input cursor presence exceeds its validated receiver");
    const std::array types{layout.keyDown, layout.keyUp, layout.mouseMove, layout.mouseDown,
                           layout.mouseUp, layout.wheel, layout.mouseEnter};
    for (size_t i = 0; i < types.size(); ++i)
        for (size_t j = 0; j < i; ++j)
            require(types[i] != types[j], "Input event kinds must be distinct");
}

void GameInputEvents::dispatch(int type, std::optional<uint32_t> key, std::optional<InputPosition> position,
                               std::optional<uint32_t> button, int32_t wheel, InputEventProgress *progress) {
    alignas(16) unsigned char event[256];
    functions.construct(event, functions.ticks() / 1000.0, type);

    struct Destroy {
        const InputEventFunctions &functions;
        void *event;

        ~Destroy() {
            functions.destroy(event, 0);
        }
    } destroy{functions, event};

    if (key)
        write(event, layout.scancode, *key);
    if (position) {
        write(event, layout.mouseX, position->x);
        write(event, layout.mouseY, position->y);
    }
    if (button)
        write(event, layout.mouseButton, *button);
    if (wheel) {
        // Control bindings consume dw; InputHandlerAgui uses dy to build its vertical wheel event.
        write(event, layout.mouseWheel, wheel);
        write(event, layout.mouseWheelY, wheel);
    }
    if (progress)
        progress->started = true;
    functions.update(state, event);
    int result;
    try {
        result = route ? functions.process(event, false) : 0;
    } catch (...) {
        // Preserve the dispatch failure while completing the normal input bookkeeping.
        try {
            functions.postUpdate(state, event);
        } catch (...) {
        }
        throw;
    }
    functions.postUpdate(state, event);
    if (progress)
        progress->completed = true;
    if (result != 0)
        throw std::runtime_error("Game event processing requested an application transition");
}

void GameInputEvents::move(InputPosition position) {
    move(position, nullptr, nullptr);
}

void GameInputEvents::move(InputPosition position, void (*validate)(void *), void *owner) {
    require(position.x >= 0 && position.y >= 0, "Viewport coordinates must be nonnegative");
    // A synthetic cursor enters the client just as a real pointer does, without changing OS focus.
    if (!read<bool>(state, layout.stateMouseInWindow))
        dispatch(layout.mouseEnter, {}, position, {});
    if (validate)
        validate(owner);
    dispatch(layout.mouseMove, {}, position, {});
}

void GameInputEvents::wheel(int32_t direction) {
    require(direction == -1 || direction == 1, "Wheel requires one up or down event");
    const InputPosition position{read<int32_t>(state, layout.stateMouseX), read<int32_t>(state, layout.stateMouseY)};
    dispatch(layout.wheel, {}, position, {}, direction);
}

void GameInputEvents::button(InputButton button, bool down) {
    require(button.code != 0, "Input button must have a resolved game code");
    require(button.device == InputDevice::Keyboard || button.device == InputDevice::Mouse, "Unsupported input device");
    auto entry = std::find_if(buttons.entries.begin(), buttons.entries.end(),
                              [&](const auto &entry) { return entry.state && entry.button == button; });
    if (down) {
        require(entry == buttons.entries.end(), "Input button already belongs to this task");
        entry = std::find_if(buttons.entries.begin(), buttons.entries.end(),
                             [](const auto &entry) { return !entry.state; });
        require(entry != buttons.entries.end(), "Input button ownership exceeds its bound");
        *entry = {button, state, {}, {}};
    } else {
        if (entry == buttons.entries.end())
            return;
        if (!entry->press.started || entry->release.completed) {
            *entry = {};
            return;
        }
        require(entry->state == state, "Input state changed before button release");
        require(!entry->release.started, "Input release did not finish; an uncertain event cannot be replayed");
    }
    auto &progress = down ? entry->press : entry->release;
    if (button.device == InputDevice::Keyboard) {
        dispatch(down ? layout.keyDown : layout.keyUp, button.code, {}, {}, 0, &progress);
    } else {
        // Use the current in-game cursor for both edges, including cancellation after another move.
        const InputPosition position{read<int32_t>(state, layout.stateMouseX),
                                     read<int32_t>(state, layout.stateMouseY)};
        dispatch(down ? layout.mouseDown : layout.mouseUp, {}, position, button.code, 0, &progress);
    }
    if (!down)
        *entry = {};
}

bool InputEventButtons::active() const {
    return std::any_of(entries.begin(), entries.end(), [](const auto &entry) { return entry.state != nullptr; });
}

void InputEventButtons::release(GameInputEvents &events) {
    std::exception_ptr failure;
    for (auto entry = entries.rbegin(); entry != entries.rend(); ++entry)
        if (entry->state) {
            try {
                events.button(entry->button, false);
            } catch (...) {
                if (!failure)
                    failure = std::current_exception();
            }
        }
    if (failure)
        std::rethrow_exception(failure);
}
