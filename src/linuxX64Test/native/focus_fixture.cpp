#include <cassert>
#include <cstddef>
#include <cstdint>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

struct FocusWidget;
struct FocusManager {
    FocusWidget *selected = nullptr;
    bool tabbed = false;
};
struct FocusGui {
    std::uint64_t padding[FIXTURE_PADDING]{};
    FocusManager manager;
};
struct FocusWidget {
    std::uint64_t padding[FIXTURE_PADDING]{};
    FocusGui *gui = nullptr;
    FocusWidget *parent = nullptr;
};
extern "C" const std::uint64_t fixture_focus_size = sizeof(FocusWidget);

extern "C" __attribute__((noinline)) void fixture_focus_manager(FocusManager *manager, FocusWidget *widget, bool tabbed) {
    manager->selected = widget;
    manager->tabbed = tabbed;
}

extern "C" __attribute__((noinline)) void fixture_widget_focus(FocusWidget *widget, bool tabbed) {
    auto *gui = widget->gui;
    if (!gui) {
        auto *parent = widget;
        do {
            parent = parent->parent;
            if (!parent)
                return;
            gui = parent->gui;
        } while (!gui);
        widget->gui = gui;
    }
    fixture_focus_manager(&gui->manager, widget, tabbed);
}

int main() {
    FocusGui gui;
    FocusWidget root, child, grandchild, orphan;
    root.gui = &gui;
    child.parent = &root;
    grandchild.parent = &child;
    fixture_widget_focus(&grandchild, true);
    assert(gui.manager.selected == &grandchild && gui.manager.tabbed && grandchild.gui == &gui);
    fixture_widget_focus(&grandchild, false);
    assert(gui.manager.selected == &grandchild && !gui.manager.tabbed);
    fixture_widget_focus(&orphan, true);
    assert(gui.manager.selected == &grandchild && !gui.manager.tabbed && !orphan.gui);
}
