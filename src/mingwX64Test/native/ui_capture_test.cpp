#include "ui_capture.h"
#include <array>
#include <cassert>
#include <cstring>
#include <stdexcept>
#include <vector>

// Synthetic fixtures deliberately use layouts unrelated to the game's types.
struct Gui {
    uint64_t padding[3];
    uintptr_t capture;
};

struct Event {
    int x, y;
    uint32_t type;
    void *source;
};

static Symbols symbols{};
static std::array<unsigned char, 128> selected{}, receiver{};
static std::vector<int> calls;
static bool alive = true, current = true, throwUp = false, replaceRoot = false;

static void *relative(void *gui, void *output, void *widget, const void *input) {
    assert(gui && widget == receiver.data());
    auto event = *static_cast<const Event *>(input);
    assert(event.x == 150 && event.y == 230);
    event.x -= 100;
    event.y -= 200;
    event.source = widget;
    memcpy(output, &event, sizeof(event));
    calls.push_back(1);
    return output;
}

static void up(void *widget, const void *input) {
    const auto &event = *static_cast<const Event *>(input);
    assert(widget == receiver.data());
    assert(event.source == widget && event.x == 50 && event.y == 30 && event.type == 37);
    calls.push_back(2);
    if (replaceRoot)
        current = false;
    if (throwUp)
        throw std::runtime_error("Fixture mouse-up failed");
}

static void clear(void *value) {
    assert(current);
    static_cast<Gui *>(value)->capture = 0;
    calls.push_back(3);
}

int main() {
    symbols.events.guiCaptureTarget = offsetof(Gui, capture);
    symbols.events.widgetTargetable = 24;
    symbols.events.eventType = offsetof(Event, type);
    symbols.events.up = 37;
    symbols.address[RelativeMouseEvent] = reinterpret_cast<uint64_t>(&relative);
    symbols.address[DispatchUp] = reinterpret_cast<uint64_t>(&up);
    symbols.address[ReleaseMouseCapture] = reinterpret_cast<uint64_t>(&clear);
    Event absolute{150, 230, 12, selected.data()};
    auto target = reinterpret_cast<uintptr_t>(receiver.data()) + symbols.events.widgetTargetable;
    auto live = [](void *widget) {
        assert(widget == receiver.data());
        return alive;
    };
    auto same = [] { return current; };
    Gui gui{};
    {
        UiCapture capture(symbols, &gui, same, live);
        gui.capture = target;
        capture.release(&absolute, selected.data());
        capture.release(&absolute, selected.data());
        assert((calls == std::vector<int>{1, 2, 3}) && !gui.capture);
    }
    calls.clear();
    {
        UiCapture capture(symbols, &gui, same, live);
        gui.capture = target;
        capture.release(&absolute, receiver.data());
        assert((calls == std::vector<int>{3}) && !gui.capture);
    }
    calls.clear();
    gui.capture = target;
    {
        UiCapture capture(symbols, &gui, same, live);
        capture.release(&absolute, nullptr);
        assert((calls == std::vector<int>{1, 2, 3}) && !gui.capture);
    }
    calls.clear();
    gui.capture = 0;
    {
        UiCapture capture(symbols, &gui, same, live);
        gui.capture = target;
        alive = false;
        capture.release(&absolute, nullptr);
        assert((calls == std::vector<int>{3}) && !gui.capture);
        alive = true;
    }
    calls.clear();
    {
        UiCapture capture(symbols, &gui, same, live);
        gui.capture = target;
        throwUp = true;
        bool failed = false;
        try {
            capture.release(&absolute, nullptr);
        } catch (const std::runtime_error &) {
            failed = true;
        }
        capture.release(&absolute, nullptr);
        assert(failed && (calls == std::vector<int>{1, 2, 3}) && !gui.capture);
        throwUp = false;
    }
    calls.clear();
    {
        UiCapture capture(symbols, &gui, same, live);
        gui.capture = target;
        replaceRoot = true;
        capture.release(&absolute, nullptr);
        assert((calls == std::vector<int>{1, 2}) && gui.capture == target);
    }
    calls.clear();
    {
        UiCapture capture(symbols, &gui, same, live);
        gui.capture = 1;
        capture.release(&absolute, nullptr);
        assert(calls.empty());
    }
    return 0;
}
