#include <cstddef>
#include <cstdint>
#include <cstdlib>

struct Targetable { uintptr_t head = 0; };
struct Widget {
    uintptr_t padding[FIXTURE_PADDING]{};
    Widget *parent = nullptr;
    Targetable targetable;
};
struct ModalEntry {
    Targetable *target = nullptr;
    uintptr_t padding[FIXTURE_PADDING]{};
};
struct Gui {
    uintptr_t padding[FIXTURE_PADDING]{};
    ModalEntry *begin = nullptr;
    ModalEntry *end = nullptr;

    Widget *getModalWidget() {
        while (end != begin) {
            if (const auto target = (end - 1)->target)
                return reinterpret_cast<Widget *>(reinterpret_cast<char *>(target) - offsetof(Widget, targetable));
            --end;
        }
        return nullptr;
    }

    bool widgetIsModalChild(Widget *widget) {
        const auto modal = getModalWidget();
        if (!modal)
            return false;
        while (widget) {
            if (widget == modal)
                return true;
            widget = widget->parent;
        }
        return false;
    }
};

extern "C" {
size_t fixture_gui_size = sizeof(Gui);
size_t fixture_widget_size = sizeof(Widget);
size_t fixture_begin = offsetof(Gui, begin);
size_t fixture_end = offsetof(Gui, end);
size_t fixture_stride = sizeof(ModalEntry);
size_t fixture_target __attribute__((section(".data"))) = offsetof(ModalEntry, target);
size_t fixture_targetable = offsetof(Widget, targetable);
size_t fixture_parent = offsetof(Widget, parent);

__attribute__((noinline)) bool fixture_modal(Gui *gui, Widget *widget) {
    return gui->widgetIsModalChild(widget);
}
}

int main() {
    Widget lower, top, child, unrelated;
    child.parent = &top;
    ModalEntry entries[4]{};
    entries[0].target = &lower.targetable;
    entries[2].target = &top.targetable;
    Gui gui;
    gui.begin = entries;
    gui.end = entries + 4;
    if (!fixture_modal(&gui, &child) || gui.end != entries + 3 || !fixture_modal(&gui, &top) ||
        fixture_modal(&gui, &lower) || fixture_modal(&gui, &unrelated))
        std::abort();
    entries[2].target = nullptr;
    if (fixture_modal(&gui, &child) || gui.end != entries + 1 || !fixture_modal(&gui, &lower))
        std::abort();
    entries[0].target = nullptr;
    if (fixture_modal(&gui, &child) || gui.end != gui.begin)
        std::abort();
}
