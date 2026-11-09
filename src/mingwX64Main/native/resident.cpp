#include "detour.h"
#include "protocol.h"
#include "screenshot.h"
#include "controls.h"
#include "frontend_input.h"
#include "widget_properties.h"
#include "ui_selector.h"
#include "ui_capture.h"
#include "world_query.h"
#include "script_gui.h"
#include "chat.h"
#include "resident_input.h"
#include "../../nativeMain/native/selector_payload.h"
#include <algorithm>
#include <atomic>
#include <cstring>
#include <functional>
#include <intrin.h>
#include <limits>
#include <unordered_set>

static Shared *shared{};
static HANDLE mapping{};
static Symbols symbols;
static Detour updateHook;
static Detour nextEventHook;
static KeyGesture keyGesture;
static InputEventButtons uiButtons;
static ResidentInput timedInput;
static Detour inputTickHook, gameDestroyHook;
static Detour presentHook, glHook;
static std::vector<void *> widgets;
static bool selectorText, selectorPrototype;
static ObservationStorage observations;
static fm::RequestStorage requestStorage;
static_assert(noexcept(observations = ObservationStorage{}));
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

static void collectText(void *widget, FmNode &node) {
    const void *text = readWidgetText(symbols, widget);
    const auto data = fn<const char *(*)(const void *)>(StringData);
    const auto size = fn<size_t (*)(const void *)>(StringSize);
    bool truncated = false;
    node.text = observations.copyText(data(text), size(text), truncated);
    if (truncated)
        node.truncated |= 1;
}

static void collect(void *widget, int depth) {
    if (InterlockedCompareExchange(&shared->cancel, 0, 0))
        return;
    auto &result = shared->result;
    require(result.count < std::numeric_limits<int>::max(), "UI node count exceeds integer representation");
    const bool destroying = fn<bool (*)(void *)>(Destroying)(widget);
    auto &node = observations.nodes.emplace_back();
    ++result.count;
    widgets.push_back(widget);
    memset(&node, 0, sizeof(node));
    node.destroying = destroying;
    node.depth = depth;
    node.guiIndex = -1;
    void *type = fn<void *(*)(void *)>(TypeId)(widget);
    const char *name = fn<const char *(*)(void *)>(TypeName)(type);
    bool truncated = false;
    node.type = observations.copyText(name, strlen(name), truncated);
    if (selectorText)
        collectText(widget, node);

    std::unordered_set<void *> ancestors;
    for (void *parent = widget; parent;) {
        require(ancestors.insert(parent).second, "Cyclic UI parent hierarchy");
        SIZE_T copied = 0;
        require(ReadProcessMemory(GetCurrentProcess(), static_cast<unsigned char *>(parent) + symbols.widgetParentOffset,
                                  &parent, sizeof(parent), &copied) && copied == sizeof(parent),
                "Unreadable UI parent pointer");
    }

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
    if (selectorPrototype)
        collectSlotIdentity(symbols, widget, node, observations, FM_UI_PROTOTYPE);
}

template <class Visit> static void walk(void *root, Visit visit, bool cancellable = true) {
    fm::ui::walk(reinterpret_cast<uintptr_t>(root), symbols.widgetSize, symbols.widgetChildren,
        std::size(symbols.widgetChildren), [](uintptr_t address, auto &value) {
            SIZE_T copied = 0;
            return ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<const void *>(address),
                                     &value, sizeof(value), &copied) && copied == sizeof(value);
        }, visit, [cancellable] { return cancellable && shared->cancel != 0; });
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
    walk(root, [&](uintptr_t p, int) {
        if (p == reinterpret_cast<uintptr_t>(widget))
            found = true;
    }, false);
    return found && !fn<bool (*)(void *)>(Destroying)(widget);
}

template <class T> static void field(unsigned char *storage, unsigned offset, T value) {
    memcpy(storage + offset, &value, sizeof(value));
}

static void performAction(void *root, void *gui) {
    const auto matches = selectWidgets(shared->result, observations, shared->action, &shared->cancel);
    require(matches.size() == 1, matches.empty() ? "UI selector matched no widget" : "UI selector is ambiguous");
    const int selected = matches.front();
    void *widget = widgets[selected];
    const auto modalChild = fn<bool (*)(void *, void *)>(ModalChild);
    const bool hasModal = std::any_of(widgets.begin(), widgets.end(), [&](void *p) { return modalChild(gui, p); });
    require(!hasModal || modalChild(gui, widget), "Widget is outside the active modal UI");
    // Postorder ancestors follow the target; verify all ancestors rather than only the widget itself.
    int depth = observations.nodes[selected].depth;
    for (int i = selected; i < shared->result.count; ++i) {
        if (i != selected && observations.nodes[i].depth >= depth)
            continue;
        require(observations.nodes[i].enabled && !fn<bool (*)(void *)>(Destroying)(widgets[i]),
                "Widget or ancestor is disabled or being destroyed");
        depth = observations.nodes[i].depth;
    }
    const auto &action = shared->action;
    const auto &layout = symbols.events;
    alignas(16) unsigned char event[256]{};
    if (action.kind == 1) {
        const auto &node = observations.nodes[selected];
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
        field<double>(event, layout.mouseTime, fn<uint64_t (*)()>(SdlTicksNS)() / 1e9);
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
        const unsigned types[] = {layout.enter, layout.down, layout.click, layout.up, layout.leave};
        // Some widgets consult ControlInput rather than only the GUI event's button/modifier fields.
        // Mirror the chord in InputState without routing a second click through the GUI or world.
        const auto functions = gameInputFunctions(symbols);
        auto stateButton = [&](InputButton button, bool pressed) {
            GameInputEvents(symbols.timedInput.events, functions, uiButtons, gameInputState(symbols), false)
                .button(button, pressed);
        };
        auto releaseState = [&] {
            GameInputEvents events(symbols.timedInput.events, functions, uiButtons, gameInputState(symbols), false);
            uiButtons.release(events);
        };
        // All releases are resident-owned; no IPC/cancellation point separates this finite gesture.
        bool down = false, entered = false;
        try {
            require(action.keyCount <= 3, "Too many UI click modifiers");
            for (unsigned i = 0; i < action.keyCount; ++i) {
                stateButton({InputDevice::Keyboard, action.keys[i]}, true);
            }
            const Symbol mouseSymbol = action.button == 1 ? MouseRight : action.button == 2 ? MouseMiddle : MouseLeft;
            uint32_t mouseCode{};
            memcpy(&mouseCode,
                   reinterpret_cast<const void *>(symbols.address[mouseSymbol] + symbols.controls.mouseValue),
                   sizeof(mouseCode));
            stateButton({InputDevice::Mouse, mouseCode}, true);
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
            require(live(widget, root, gui), "Text target disappeared before dispatch completed");
            memset(event, 0, sizeof(event));
            field<unsigned>(event, layout.key, key);
            field<unsigned>(event, layout.extKey, layout.extKeyNone);
            field<unsigned>(event, layout.unichar, character);
            field<bool>(event, layout.keyControl, control);
            field<void *>(event, layout.keySource, widget);
            field<double>(event, layout.keyTime, fn<uint64_t (*)()>(SdlTicksNS)() / 1e9);
            fn<bool (*)(void *, const void *)>(TextKeyDown)(textBox, event);
        };
        key(layout.keyA, 0, true);
        key(layout.keyBackspace, 0, false);
        require(action.textCount <= FM_UI_TEXT_CHARACTERS, "UI text exceeds native command storage");
        for (unsigned i = 0; i < action.textCount; ++i)
            key(layout.keyNone, action.text[i], false);
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
    if (shared && enabled && uiButtons.active()) {
        // Keep the original action result and release ownership across frontend phases. Only cleanup is resumed.
        try {
            const auto functions = gameInputFunctions(symbols);
            GameInputEvents events(symbols.timedInput.events, functions, uiButtons, gameInputState(symbols), false);
            uiButtons.release(events);
        } catch (const std::exception &) {
            return;
        }
        InterlockedExchange(&shared->command, 3);
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
    result.imageWidth = 0;
    result.imageHeight = 0;
    result.controlCount = 0;
    result.optionCount = 0;
    result.registryCount = 0;
    result.worldSize = 0;
    result.guiStatus = 0;
    result.guiMessage[0] = 0;
    result.chat = {};
    result.textRange = {};
    observations = ObservationStorage{};
    observations.publish(result);
    try {
        if (shared->cancel)
            throw std::runtime_error("Tool was cancelled before UI traversal");
        if (shared->operation == 13) {
            shared->request = requestStorage.reserve(shared->requestSize);
        } else {
            require(requestStorage.owns(shared->action.path) && requestStorage.owns(shared->worldQuery.source) &&
                        requestStorage.owns(shared->worldQuery.arguments) && requestStorage.owns(shared->bindingQuery),
                    "Request byte range is not owned");
            require(shared->action.collection <= 1 && shared->action.optionOffset <= INT32_MAX &&
                        shared->action.optionLimit <= INT32_MAX && shared->action.maxDepth >= 0 &&
                        !(shared->action.fields & ~FM_UI_ALL_FIELDS),
                    "Invalid UI collection bounds");
        }
        if (shared->operation == 7) {
            require(!loading, "Control registry is unavailable during loading");
            collectControls(symbols, *shared, observations);
        }
        if (shared->operation == 8) {
            require(!loading && game, "World query requires a loaded world; menus and loading are unavailable");
            queryWorld(symbols, game, shared->worldQuery, result, observations);
        }
        if (shared->operation == 9) {
            require(symbols.timedInput.supported, "Timed input adapter is unavailable");
            timedInput.admit(symbols, shared->inputName, sizeof(shared->inputName));
            InterlockedExchange(&shared->inputActive, timedInput.active());
        }
        if (shared->operation == 10 || shared->operation == 11) {
            require(!loading && game, "Chat requires a loaded world");
            if (shared->operation == 10) {
                readChat(symbols, game, result.chat, observations, &shared->cancel);
                selectChatText(observations.chatRecords, shared->chatRead, observations.strings);
            } else
                sendChat(symbols, game, shared->chat);
        }
        const bool keyAction = shared->operation == 5 && shared->action.kind == 3;
        if (keyAction) {
            require(symbols.input.supported, "Frontend keyboard adapter is unavailable");
        }
        if (keyAction && !shared->action.count) {
            keyGesture.begin(shared->action.keys, shared->action.keyCount);
        } else if (shared->operation == 3 || shared->operation == 5) {
            require(shared->limit > 0 && shared->limit <= uint32_t(std::numeric_limits<int>::max()),
                    "Invalid UI observation limit");
            widgets.clear();
            selectorText = false;
            selectorPrototype = false;
            if (shared->action.count) for (const auto &step : fm::ui::selectorPath(shared->action.path, shared->action.count)) {
                selectorText |= step.text.has_value();
                selectorPrototype |= step.prototypeName.has_value() || step.prototypeType.has_value();
            }
            walk(root, [](uintptr_t widget, int depth) { collect(reinterpret_cast<void *>(widget), depth); });
            require(!shared->cancel, "Tool cancelled before UI dispatch");
            if (shared->operation == 3 || hasGuiPredicates(shared->action))
                prepareScriptGui(symbols, loading ? nullptr : game, shared->worldQuery, shared->action,
                                 widgets, result, observations, &shared->cancel);
            if (shared->operation == 5)
                performAction(root, gui);
            else {
                if (shared->action.count)
                    selectWidgets(result, observations, shared->action, &shared->cancel);
                auto observed = observationWidgets(result, observations, shared->action.count != 0,
                                                   shared->action.collection != 0, shared->action.maxDepth);
                if (shared->action.collection) {
                    observations.optionOffset = shared->action.optionOffset;
                    observations.optionLimit = shared->action.optionLimit;
                }
                IconReferences icons(symbols);
                const auto fields = shared->action.fields;
                for (int i : observed) {
                    require(!shared->cancel, "Tool cancelled during UI property observation");
                    void *widget = widgets[i];
                    auto &node = observations.nodes[i];
                    if ((fields & FM_UI_TEXT) && !selectorText)
                        collectText(widget, node);
                    collectSlotIdentity(symbols, widget, node, observations, fields);
                    collectWidgetProperties(symbols, widget, node, &result, &observations, &shared->cancel, fields);
                    if (fields & FM_UI_NUMBER) collectNumber(symbols, widget, node);
                    if (fields & FM_UI_PROGRESS) collectProgress(symbols, widget, node);
                    if (fields & FM_UI_SWITCH) collectSwitch(symbols, widget, node);
                    if (fields & FM_UI_ELEMENT) collectElement(symbols, widget, node, observations);
                    if (fields & FM_UI_QUALITY_CONDITION) collectQualityCondition(symbols, widget, node);
                    if (fields & FM_UI_ICONS) icons.collect(widget, node, &observations);
                    if (fields & FM_UI_IMAGE) icons.collectImage(widget, node, observations);
                    if (fields & FM_UI_TOOLTIP) collectTooltip(symbols, widget, node, observations);
                    if (fields & FM_UI_GRAPH) collectGraph(symbols, widget, node, observations, shared->action.graph, &shared->cancel);
                    if (fields & FM_UI_ACHIEVEMENT) collectAchievement(symbols, widget, node);
                }
                observeScriptGui(symbols, game, shared->worldQuery, observed, result, observations);
                if (shared->action.textSlice.source) {
                    require(shared->action.collection && observed.size() == 1, "Text slicing requires one widget");
                    auto &node = observations.nodes[observed.front()];
                    const auto &request = shared->action.textSlice;
                    result.textRange = sliceUiText(node, observations.options, request, true,
                        (node.properties & 16) != 0, observations.strings);
                }
                retainObservedWidgets(result, observations, observed, fields);
            }
        }
        if (keyGesture.active()) {
            widgets.clear();
            return;
        }
        if (shared->cancel)
            throw std::runtime_error("Tool was cancelled during UI traversal");
        if (shared->operation == 12) {
            require(!loading, "Rendering control is unavailable during loading");
            auto control = symbols.renderingControl;
            control.enabled = shared->renderingEnabled;
            const int applied = setRendering(control, [](uint64_t address, auto &value) {
                SIZE_T copied = 0;
                return ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<const void *>(address),
                                         &value, sizeof(value), &copied) && copied == sizeof(value);
            }, [](uint64_t address, uint8_t value) {
                SIZE_T copied = 0;
                return WriteProcessMemory(GetCurrentProcess(), reinterpret_cast<void *>(address),
                                          &value, sizeof(value), &copied) && copied == sizeof(value);
            });
            require(applied == FM_RENDERING_APPLIED, "Cannot safely set native rendering");
        }
        if (shared->operation == 6) {
            const int admission = renderingAdmission(symbols.rendering, [](uint64_t address, auto &value) {
                SIZE_T copied = 0;
                return ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<const void *>(address),
                                         &value, sizeof(value), &copied) && copied == sizeof(value);
            });
            require(admission != FM_RENDERING_SUSPENDED, "Screenshot unavailable while rendering is suspended.");
            require(admission == FM_RENDERING_AVAILABLE, "Cannot read the native rendering condition");
            return;
        }
    } catch (const std::exception &error) {
        result.error = 1;
        strncpy_s(result.message, error.what(), _TRUNCATE);
        result.count = 0;
    }
    widgets.clear();
    observations.publish(result);
    if (uiButtons.active())
        return;
    if (shared->operation != 13) {
        requestStorage.clear();
        shared->request = {};
        shared->action.path = {};
        shared->worldQuery.source = {};
        shared->worldQuery.arguments = {};
    }
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
            captureFrame(swapChain, shared->result, observations);
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
            require(!uiButtons.active(), "UI button cleanup must finish before unhook");
            Detour::change(hooks, false);
            while (callbacks.load())
                Sleep(1);
            if (shared->command == 1 || shared->command == 2) {
                shared->result.error = 1;
                strcpy_s(shared->result.message, "Tool aborted: detached");
                InterlockedExchange(&shared->command, 3);
            }
            observations = ObservationStorage{};
            requestStorage.clear();
            shared->request = {};
            shared->action.path = {};
            shared->worldQuery.source = {};
            shared->worldQuery.arguments = {};
            shared->result.count = 0;
            shared->result.optionCount = 0;
            shared->result.controlCount = 0;
            shared->result.worldSize = 0;
            shared->result.chat = {};
            observations.publish(shared->result);
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
