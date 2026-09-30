#include "targeter_fixture.h"
#include <cstddef>
#include <cstdlib>

struct Gui {
    uintptr_t padding[FIXTURE_PADDING]{};
    Targeter hovered;
    Targeter captured;
    bool dragging = true;
    uint64_t origin = 0;
};

struct Widget {
    uintptr_t padding[FIXTURE_PADDING];
    Targetable targetable;
};

static unsigned work = 0;

extern "C" {
size_t fixture_gui_size = sizeof(Gui);
size_t fixture_gate_offset = offsetof(Gui, captured) + offsetof(Targeter, target);
size_t fixture_capture_offset = offsetof(Gui, captured);
size_t fixture_widget_base = offsetof(Widget, targetable);
size_t fixture_dragging = offsetof(Gui, dragging);
size_t fixture_origin = offsetof(Gui, origin);
size_t fixture_target = offsetof(Targeter, target);
size_t fixture_previous = offsetof(Targeter, previous);
size_t fixture_next = offsetof(Targeter, next);
size_t fixture_head = offsetof(Targetable, head);

__attribute__((noinline)) void fixture_hover_work(Gui *) {
    ++work;
}

__attribute__((noinline)) void fixture_hover(Gui *gui) {
    if (gui->captured.target)
        return;
    fixture_hover_work(gui);
}

__attribute__((noinline)) void fixture_destroy(Gui *gui, Widget *widget) {
    fixture_hover_work(gui);
    Widget *captured = gui->captured.target ? reinterpret_cast<Widget *>(
        reinterpret_cast<char *>(gui->captured.target) - offsetof(Widget, targetable)) : nullptr;
    if (captured == widget) {
        auto &targeter = gui->captured;
        if (targeter.target) {
            if (targeter.previous)
                targeter.previous->next = targeter.next;
            else
                targeter.target->head = targeter.next;
            if (targeter.next)
                targeter.next->previous = targeter.previous;
            targeter.target = nullptr;
            targeter.previous = nullptr;
            targeter.next = nullptr;
        }
        gui->dragging = false;
        gui->origin = 0x7fffffff7fffffffULL;
    }
    ++gui->padding[0];
}
}

int main() {
    Gui gui;
    fixture_hover(&gui);
    if (work != 1)
        std::abort();
    Targetable owner;
    gui.captured.target = &owner;
    fixture_hover(&gui);
    if (work != 1)
        std::abort();
    Widget widget{};
    gui.captured.target = &widget.targetable;
    widget.targetable.head = &gui.captured;
    fixture_destroy(&gui, &widget);
    if (gui.captured.target || widget.targetable.head || gui.dragging || gui.origin != 0x7fffffff7fffffffULL)
        std::abort();
}
