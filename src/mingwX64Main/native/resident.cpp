#include "detour.h"
#include "protocol.h"
#include "screenshot.h"
#include "controls.h"
#include "frontend_input.h"
#include "widget_properties.h"
#include "ui_selector.h"
#include "ui_capture.h"
#include "world_query.h"
#include "resident_input.h"
#include <algorithm>
#include <atomic>
#include <cstring>
#include <functional>
#include <intrin.h>
#include <limits>

static Shared *shared{};
static HANDLE mapping{};
static Symbols symbols;
static Detour updateHook;
static Detour nextEventHook;
static KeyGesture keyGesture;
static ResidentInput timedInput;
static Detour inputTickHook, gameDestroyHook;
static Detour presentHook, glHook;
static std::vector<void *> widgets;
static std::atomic<unsigned> callbacks{};
static std::atomic<bool> enabled{};
static SRWLOCK lifecycle = SRWLOCK_INIT;

template <class T> static T fn(Symbol s) {
    return reinterpret_cast<T>(symbols.address[s]);
}

static bool within(void *p, uint64_t begin, uint64_t end) {
    auto n = reinterpret_cast<uint64_t>(p);
    return n >= begin && n < end;
}

struct Guard {
    Guard() {
        callbacks++;
    }

    ~Guard() {
        callbacks--;
    }
};

static void copyText(char *out, size_t capacity, const char *text, size_t length, FmNode &node, int flag) {
    size_t n = std::min(capacity - 1, length);
    if (n < length) {
        node.truncated |= flag;
        while (n > 0 && (static_cast<unsigned char>(text[n]) & 0xc0) == 0x80)
            n--;
    }
    memcpy(out, text, n);
    out[n] = 0;
}

static void collect(void *widget) {
    if (InterlockedCompareExchange(&shared->cancel, 0, 0))
        return;
    auto &result = shared->result;
    if (result.count >= int(shared->limit)) {
        result.truncated = 1;
        return;
    }
    if (fn<bool (*)(void *)>(Destroying)(widget))
        return;
    auto &node = result.nodes[result.count++];
    widgets.push_back(widget);
    memset(&node, 0, sizeof(node));
    void *type = fn<void *(*)(void *)>(TypeId)(widget);
    const char *name = fn<const char *(*)(void *)>(TypeName)(type);
    copyText(node.type, sizeof(node.type), name, strlen(name), node, 2);
    Symbol getter = Text;
    if (strcmp(name, "class agui::Label") == 0)
        getter = LabelText;
    void *textReceiver = fn<void *(*)(void *, long, void *, void *, int)>(DynamicCast)(
        widget, 0, reinterpret_cast<void *>(symbols.address[WidgetType]),
        reinterpret_cast<void *>(symbols.address[TextBoxType]), 0);
    if (textReceiver)
        getter = TextBoxText;
    void *text = fn<void *(*)(void *)>(getter)(textReceiver ? textReceiver : widget);
    auto data = fn<const char *(*)(void *)>(StringData);
    auto size = fn<size_t (*)(void *)>(StringSize);
    copyText(node.text, sizeof(node.text), data(text), size(text), node, 1);

    struct Rect {
        int x, y, width, height;
    } rectangle{};

    fn<void *(*)(void *, Rect *)>(WidgetRectangle)(widget, &rectangle);
    node.x = rectangle.x;
    node.y = rectangle.y;
    node.width = rectangle.width;
    node.height = rectangle.height;
    node.enabled = fn<bool (*)(void *)>(Enabled)(widget);
    collectVisibility(symbols, widget, node);
    collectSlotIdentity(symbols, widget, node);
    auto parent = [](void *p) {
        return *reinterpret_cast<void **>(static_cast<unsigned char *>(p) + symbols.widgetParentOffset);
    };
    for (void *p = parent(widget); p; p = parent(p)) {
        require(++node.depth <= FM_MAX_NODES, "UI ancestry exceeds supported bound");
    }
}

static bool currentGui(void *root, void *gui) {
    auto *current = *reinterpret_cast<unsigned char **>(symbols.address[GuiInstance]);
    return current && current == gui && *reinterpret_cast<void **>(current + symbols.guiRootOffset) == root;
}

// Re-traverse after callbacks: dispatch may remove the target, its window, or the complete root.
static bool live(void *widget, void *root, void *gui) {
    if (!currentGui(root, gui))
        return false;
    bool found = false;
    const std::function<void(void *)> visitor = [&](void *p) {
        if (p == widget)
            found = true;
    };
    fn<void (*)(void *, const std::function<void(void *)> &)>(Walk)(root, visitor);
    return found && !fn<bool (*)(void *)>(Destroying)(widget);
}

template <class T> static void field(unsigned char *storage, unsigned offset, T value) {
    memcpy(storage + offset, &value, sizeof(value));
}

static void performAction(void *root, void *gui) {
    const auto matches = selectWidgets(shared->result, shared->action);
    require(matches.size() == 1, matches.empty() ? "UI selector matched no widget" : "UI selector is ambiguous");
    const int selected = matches.front();
    void *widget = widgets[selected];
    const auto modalChild = fn<bool (*)(void *, void *)>(ModalChild);
    const bool hasModal = std::any_of(widgets.begin(), widgets.end(), [&](void *p) { return modalChild(gui, p); });
    require(!hasModal || modalChild(gui, widget), "Widget is outside the active modal UI");
    // Postorder ancestors follow the target; verify all ancestors rather than only the widget itself.
    int depth = shared->result.nodes[selected].depth;
    for (int i = selected; i < shared->result.count; ++i) {
        if (i != selected && shared->result.nodes[i].depth >= depth)
            continue;
        require(shared->result.nodes[i].enabled && !fn<bool (*)(void *)>(Destroying)(widgets[i]),
                "Widget or ancestor is disabled or being destroyed");
        depth = shared->result.nodes[i].depth;
    }
    const auto &action = shared->action;
    const auto &layout = symbols.events;
    alignas(16) unsigned char event[256]{};
    if (action.kind == 1) {
        const auto &node = shared->result.nodes[selected];
        require(node.width > 0 && node.height > 0, "Widget has no interaction area");
        field<int>(event, layout.mouseX, int((node.width - 1) * action.x));
        field<int>(event, layout.mouseY, int((node.height - 1) * action.y));
        field<uint16_t>(event, layout.button,
                        uint16_t(action.button == 1   ? layout.right
                                 : action.button == 2 ? layout.middle
                                                      : layout.left));
        field<bool>(event, layout.mouseAlt, action.alt != 0);
        field<bool>(event, layout.mouseControl, action.control != 0);
        field<bool>(event, layout.mouseShift, action.shift != 0);
        field<void *>(event, layout.mouseSource, widget);
        field<double>(event, layout.mouseTime, GetTickCount64() / 1000.0);
        alignas(16) unsigned char absoluteEvent[256];
        memcpy(absoluteEvent, event, sizeof(event));
        const auto coordinate = [](int origin, int extent, double fraction) {
            const int64_t value = int64_t(origin) + int((extent - 1) * fraction);
            require(value >= std::numeric_limits<int>::min() && value <= std::numeric_limits<int>::max(),
                    "UI gesture coordinate exceeds native range");
            return int(value);
        };
        field<int>(absoluteEvent, layout.mouseX, coordinate(node.x, node.width, action.x));
        field<int>(absoluteEvent, layout.mouseY, coordinate(node.y, node.height, action.y));
        UiCapture capture(
            symbols, gui, [&] { return currentGui(root, gui); }, [&](void *target) { return live(target, root, gui); });
        void *releasedWidget = nullptr;
        const Symbol dispatch[] = {DispatchEnter, DispatchDown, DispatchClick, DispatchUp, DispatchLeave};
        const unsigned types[] = {layout.enter, layout.down, layout.up, layout.up, layout.leave};
        // Some widgets consult ControlInput rather than only the GUI event's button/modifier fields.
        // Mirror the chord in InputState without routing a second click through the GUI or world.
        const auto functions = gameInputFunctions(symbols);
        std::vector<InputButton> held;
        held.reserve(4);
        auto stateButton = [&](InputButton button, bool pressed) {
            GameInputEvents(symbols.timedInput.events, functions, gameInputState(symbols), false)
                .button(button, pressed);
        };
        auto releaseState = [&] {
            while (!held.empty()) {
                stateButton(held.back(), false);
                held.pop_back();
            }
        };
        // All releases are resident-owned; no IPC/cancellation point separates this finite gesture.
        bool down = false, entered = false;
        try {
            require(action.keyCount <= 3, "Too many UI click modifiers");
            for (unsigned i = 0; i < action.keyCount; ++i) {
                held.push_back({InputDevice::Keyboard, action.keys[i]});
                stateButton(held.back(), true);
            }
            const Symbol mouseSymbol = action.button == 1 ? MouseRight : action.button == 2 ? MouseMiddle : MouseLeft;
            uint32_t mouseCode{};
            memcpy(&mouseCode,
                   reinterpret_cast<const void *>(symbols.address[mouseSymbol] + symbols.controls.mouseValue),
                   sizeof(mouseCode));
            held.push_back({InputDevice::Mouse, mouseCode});
            stateButton(held.back(), true);
            for (unsigned i = 0; i < 5; ++i) {
                if (!live(widget, root, gui))
                    break;
                if (dispatch[i] == DispatchClick &&
                    (*reinterpret_cast<const unsigned *>(static_cast<unsigned char *>(widget) + layout.widgetFlags) &
                     layout.fireOnDown))
                    continue;
                if (dispatch[i] == DispatchEnter)
                    entered = true;
                if (dispatch[i] == DispatchDown)
                    down = true;
                if (dispatch[i] == DispatchUp) {
                    down = false;
                    releasedWidget = widget;
                }
                if (dispatch[i] == DispatchLeave)
                    entered = false;
                field<unsigned>(event, layout.eventType, types[i]);
                fn<void (*)(void *, const void *)>(dispatch[i])(widget, event);
            }
            capture.release(absoluteEvent, releasedWidget);
            releaseState();
        } catch (...) {
            // Do not replay a click or dereference a target destroyed by an earlier callback.
            try {
                if (down && live(widget, root, gui)) {
                    field<unsigned>(event, layout.eventType, layout.up);
                    releasedWidget = widget;
                    fn<void (*)(void *, const void *)>(DispatchUp)(widget, event);
                }
            } catch (...) {
            }
            try {
                capture.release(absoluteEvent, releasedWidget);
            } catch (...) {
            }
            try {
                if (entered && live(widget, root, gui)) {
                    field<unsigned>(event, layout.eventType, layout.leave);
                    fn<void (*)(void *, const void *)>(DispatchLeave)(widget, event);
                }
            } catch (...) {
            }
            try {
                releaseState();
            } catch (...) {
            }
            throw;
        }
    } else if (action.kind == 2) {
        void *textBox = fn<void *(*)(void *, long, void *, void *, int)>(DynamicCast)(
            widget, 0, reinterpret_cast<void *>(symbols.address[WidgetType]),
            reinterpret_cast<void *>(symbols.address[TextBoxType]), 0);
        require(textBox != nullptr, "set_text requires a text box");
        fn<void (*)(void *, bool)>(FocusWidget)(widget, false);
        const auto key = [&](unsigned key, unsigned character, bool control) {
            if (!live(widget, root, gui))
                return false;
            memset(event, 0, sizeof(event));
            field<unsigned>(event, layout.key, key);
            field<unsigned>(event, layout.extKey, layout.extKeyNone);
            field<unsigned>(event, layout.unichar, character);
            field<bool>(event, layout.keyControl, control);
            field<void *>(event, layout.keySource, widget);
            field<double>(event, layout.keyTime, GetTickCount64() / 1000.0);
            fn<bool (*)(void *, const void *)>(TextKeyDown)(textBox, event);
            return true;
        };
        if (key(layout.keyA, 0, true) && key(layout.keyBackspace, 0, false))
            for (unsigned i = 0; i < action.textCount && i < 1024; ++i)
                if (!key(layout.keyNone, action.text[i], false))
                    break;
    } else if (action.kind == 3) {
        fn<void (*)(void *, bool)>(FocusWidget)(widget, false);
        keyGesture.begin(action.keys, action.keyCount);
    } else {
        throw std::runtime_error("Unsupported UI action");
    }
    shared->result.count = 0;
}

static void service(void *root, void *gui) {
    if (enabled) {
        if (shared && shared->inputCancel)
            timedInput.cancel("Input cancelled by detach");
        timedInput.frontend(symbols);
        if (shared)
            InterlockedExchange(&shared->inputActive, timedInput.active());
    }
    if (!shared || !enabled || !root || *reinterpret_cast<void **>(symbols.address[GuiInstance]) != gui)
        return;
    if (fn<void *(*)(void *)>(GetGui)(root) != gui)
        return;
    void *global = *reinterpret_cast<void **>(symbols.address[Global]);
    if (!global)
        return;
    bool loading = fn<bool (*)(void *)>(Loading)(global);
    void *game = loading ? nullptr : fn<void *(*)(void *)>(GetGame)(global);
    int paused = -1;
    if (symbols.pauseSupported) {
        paused = 0;
        if (game) {
            auto *map =
                *reinterpret_cast<const unsigned char **>(static_cast<unsigned char *>(game) + symbols.gameMapOffset);
            if (map)
                paused = map[symbols.mapPausedOffset] != 0 || map[symbols.mapStopLevelOffset] != 0;
        }
    }
    InterlockedExchange(&shared->state, loading ? 3 : game ? (paused == 1 ? 4 : 2) : 1);
    InterlockedIncrement64(&shared->frame);
    if (shared->operation == 6 && shared->command == 2 && shared->cancel) {
        shared->result.error = 1;
        strcpy_s(shared->result.message, "Screenshot cancelled before frame capture");
        InterlockedExchange(&shared->command, 3);
    }
    if (InterlockedCompareExchange(&shared->command, 2, 1) != 1)
        return;
    auto &result = shared->result;
    result.error = 0;
    result.message[0] = 0;
    result.count = 0;
    result.truncated = 0;
    result.state = shared->state;
    result.attached = shared->attached;
    result.frame = shared->frame;
    result.paused = paused;
    result.imageSize = 0;
    result.controlCount = 0;
    result.optionCount = 0;
    result.spriteCount = 0;
    result.registryCount = 0;
    result.worldSize = 0;
    try {
        if (shared->cancel)
            throw std::runtime_error("Tool was cancelled before UI traversal");
        if (shared->operation == 7) {
            require(!loading, "Control registry is unavailable during loading");
            collectControls(symbols, *shared);
        }
        if (shared->operation == 8) {
            require(!loading && game, "World query requires a loaded world; menus and loading are unavailable");
            queryWorld(symbols, game, shared->worldQuery, result);
        }
        if (shared->operation == 9) {
            require(symbols.timedInput.supported, "Timed input adapter is unavailable");
            timedInput.admit(symbols, shared->inputName, sizeof(shared->inputName));
            InterlockedExchange(&shared->inputActive, timedInput.active());
        }
        const bool keyAction = shared->operation == 5 && shared->action.kind == 3;
        if (keyAction) {
            require(symbols.input.supported, "Frontend keyboard adapter is unavailable");
        }
        if (keyAction && !shared->action.count) {
            keyGesture.begin(shared->action.keys, shared->action.keyCount);
        } else if (shared->operation == 3 || shared->operation == 5) {
            widgets.clear();
            const std::function<void(void *)> visitor = collect;
            fn<void (*)(void *, const std::function<void(void *)> &)>(Walk)(root, visitor);
            require(!shared->cancel, "Tool cancelled before UI dispatch");
            if (shared->operation == 5)
                performAction(root, gui);
            else {
                if (shared->action.count)
                    selectWidgets(result, shared->action);
                SpriteSnapshot sprites(symbols, result);
                for (int i : observationWidgets(result, shared->action.count != 0)) {
                    require(!shared->cancel, "Tool cancelled during UI property observation");
                    void *widget = widgets[i];
                    auto &node = result.nodes[i];
                    collectWidgetProperties(symbols, widget, node, &result);
                    collectNumber(symbols, widget, node);
                    collectProgress(symbols, widget, node);
                    collectSwitch(symbols, widget, node);
                    collectElement(symbols, widget, node);
                    collectQualityCondition(symbols, widget, node);
                    sprites.collect(widget, node);
                }
            }
        }
        if (keyGesture.active()) {
            widgets.clear();
            return;
        }
        if (shared->cancel)
            throw std::runtime_error("Tool was cancelled during UI traversal");
        if (shared->operation == 6)
            return;
    } catch (const std::exception &error) {
        result.error = 1;
        strncpy_s(result.message, error.what(), _TRUNCATE);
        result.count = 0;
    }
    widgets.clear();
    InterlockedExchange(&shared->command, 3);
}

static void *nextEvent(void *receiver, void *output) {
    Guard guard;
    auto original = reinterpret_cast<void *(*)(void *, void *)>(nextEventHook.trampoline());
    const bool eligible = enabled && shared && keyGesture.active() &&
                          within(_ReturnAddress(), symbols.address[ProcessEvents], symbols.processEventsEnd);
    void *result = original(receiver, output);
    if (!eligible || static_cast<unsigned char *>(output)[symbols.input.optionalEngaged])
        return result;
    if (shared->cancel)
        keyGesture.cancel();
    uint32_t key;
    bool down;
    if (keyGesture.next(key, down)) {
        writeKeyboardEvent(symbols, output, key, down);
    } else {
        if (keyGesture.aborted()) {
            shared->result.error = 1;
            strcpy_s(shared->result.message, "Key gesture cancelled after input release");
        }
        InterlockedExchange(&shared->command, 3);
    }
    return result;
}

static HRESULT present(IDXGISwapChain *swapChain, UINT sync, UINT flags) {
    Guard guard;
    if (!(flags & DXGI_PRESENT_TEST) && enabled && shared && shared->operation == 6 && shared->command == 2) {
        try {
            require(!shared->cancel, "Screenshot cancelled before capture");
            captureFrame(swapChain, shared->result);
        } catch (const std::exception &error) {
            shared->result.error = 1;
            strncpy_s(shared->result.message, error.what(), _TRUNCATE);
        }
        InterlockedExchange(&shared->command, 3);
    }
    return reinterpret_cast<HRESULT (*)(IDXGISwapChain *, UINT, UINT)>(presentHook.trampoline())(swapChain, sync,
                                                                                                 flags);
}

static void glSwap(void *receiver) {
    Guard guard;
    if (enabled && shared && shared->operation == 6 && shared->command == 2) {
        shared->result.error = 1;
        strcpy_s(shared->result.message, "Screenshot currently requires Factorio's DirectX renderer");
        InterlockedExchange(&shared->command, 3);
    }
    reinterpret_cast<void (*)(void *)>(glHook.trampoline())(receiver);
}

static void updateGui(void *receiver, bool value) {
    Guard guard;
    auto original = reinterpret_cast<void (*)(void *, bool)>(updateHook.trampoline());
    if (!enabled || !within(_ReturnAddress(), symbols.address[Prepare], symbols.prepareEnd)) {
        original(receiver, value);
        return;
    }
    original(receiver, value);
    auto *gui = *reinterpret_cast<unsigned char **>(symbols.address[GuiInstance]);
    if (gui)
        service(*reinterpret_cast<void **>(gui + symbols.guiRootOffset), gui);
}

static void sendInputStateChanges(void *receiver) {
    Guard guard;
    auto original = reinterpret_cast<void (*)(void *)>(inputTickHook.trampoline());
    if (enabled) {
        if (shared && shared->inputCancel)
            timedInput.cancel("Input cancelled by detach");
        timedInput.evaluate(symbols, receiver, original);
        if (shared)
            InterlockedExchange(&shared->inputActive, timedInput.active());
    } else
        original(receiver);
}

static void destroyGame(void *receiver) {
    Guard guard;
    timedInput.worldDestroyed(receiver);
    reinterpret_cast<void (*)(void *)>(gameDestroyHook.trampoline())(receiver);
}

extern "C" __declspec(dllexport) DWORD WINAPI fm_bootstrap(void *argument) {
    AcquireSRWLockExclusive(&lifecycle);
    DWORD result = 0;
    try {
        auto &input = *static_cast<Bootstrap *>(argument);
        if (!shared) {
            mapping = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, sizeof(Shared),
                                         mappingName(GetCurrentProcessId()).c_str());
            require(mapping != nullptr, "Cannot create resident IPC");
            require(GetLastError() != ERROR_ALREADY_EXISTS, "Another resident already owns this IPC protocol");
            shared = static_cast<Shared *>(MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(Shared)));
            require(shared != nullptr, "Cannot map resident IPC");
        }
        if (!shared->attached) {
            symbols = input.symbols;
            if (!updateHook.trampoline())
                updateHook.prepare(reinterpret_cast<void *>(symbols.address[UpdateGui]),
                                   reinterpret_cast<void *>(updateGui));
            if (!presentHook.trampoline())
                presentHook.prepare(reinterpret_cast<void *>(symbols.address[Present]),
                                    reinterpret_cast<void *>(present));
            if (!glHook.trampoline())
                glHook.prepare(reinterpret_cast<void *>(symbols.address[GlSwap]), reinterpret_cast<void *>(glSwap));
            if (symbols.input.supported && !nextEventHook.trampoline())
                nextEventHook.prepare(reinterpret_cast<void *>(symbols.address[NextEvent]),
                                      reinterpret_cast<void *>(nextEvent));
            if (symbols.timedInput.supported) {
                try {
                    require(symbols.world.supported && symbols.pauseSupported,
                            "Timed input requires world and pause adapters");
                    if (!inputTickHook.trampoline())
                        inputTickHook.prepare(reinterpret_cast<void *>(symbols.address[SendInputStateChanges]),
                                              reinterpret_cast<void *>(sendInputStateChanges));
                    if (!gameDestroyHook.trampoline())
                        gameDestroyHook.prepare(reinterpret_cast<void *>(symbols.address[GameDestructor]),
                                                reinterpret_cast<void *>(destroyGame));
                } catch (const std::exception &) {
                    // An unsupported optional prologue must not disable the established UI tools.
                    symbols.timedInput.supported = 0;
                }
            }
            shared->cancel = 0;
            shared->inputCancel = 0;
            shared->inputActive = 0;
            shared->command = 0;
            shared->state = 0;
            shared->frame = 0;
            std::vector<Detour *> hooks{&updateHook, &presentHook, &glHook};
            if (symbols.input.supported)
                hooks.push_back(&nextEventHook);
            if (symbols.timedInput.supported) {
                hooks.push_back(&inputTickHook);
                hooks.push_back(&gameDestroyHook);
            }
            Detour::change(hooks, true);
            enabled = true;
            InterlockedExchange(&shared->attached, 1);
        }
    } catch (const std::exception &e) {
        result = 1;
        if (shared) {
            shared->result.error = 1;
            strncpy_s(shared->result.message, e.what(), _TRUNCATE);
        }
    }
    ReleaseSRWLockExclusive(&lifecycle);
    return result;
}

extern "C" __declspec(dllexport) DWORD WINAPI fm_unhook(void *) {
    AcquireSRWLockExclusive(&lifecycle);
    DWORD result = 0;
    try {
        if (shared && shared->attached) {
            InterlockedExchange(&shared->cancel, 1);
            enabled = false;
            std::vector<Detour *> hooks{&updateHook, &presentHook, &glHook};
            if (symbols.input.supported)
                hooks.push_back(&nextEventHook);
            if (symbols.timedInput.supported) {
                hooks.push_back(&inputTickHook);
                hooks.push_back(&gameDestroyHook);
            }
            require(!timedInput.active(), "Timed input cleanup must finish before unhook");
            require(!keyGesture.active(), "Key gesture cleanup must finish before unhook");
            Detour::change(hooks, false);
            while (callbacks.load())
                Sleep(1);
            if (shared->command == 1 || shared->command == 2) {
                shared->result.error = 1;
                strcpy_s(shared->result.message, "Tool aborted: detached");
                InterlockedExchange(&shared->command, 3);
            }
            InterlockedExchange(&shared->attached, 0);
        }
    } catch (const std::exception &e) {
        result = 1;
        if (shared) {
            // A failed patch transaction preserves the original hook; keep servicing retry/status requests.
            enabled = shared->attached != 0;
            shared->result.error = 1;
            strncpy_s(shared->result.message, e.what(), _TRUNCATE);
        }
    }
    ReleaseSRWLockExclusive(&lifecycle);
    return result;
}
