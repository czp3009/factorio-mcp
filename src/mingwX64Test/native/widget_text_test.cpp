#include "widget_properties.h"
#include <cassert>
#include <string>

namespace {
struct Widget {
    virtual ~Widget() = default;
    std::string caption = "base caption";
};

struct Padding {
    virtual ~Padding() = default;
    unsigned char bytes[39]{};
};

struct Label : Padding, Widget {
    std::string text = "label text";
};

struct RichLabel : Label {};

struct TextBox : Padding, Widget {
    std::string text = "editable text";
};

int widgetType, labelType, textBoxType;

void *cast(void *value, long adjustment, void *source, void *target, int reference) {
    assert(adjustment == 0 && source == &widgetType && reference == 0);
    auto *widget = static_cast<Widget *>(value);
    if (target == &labelType)
        return dynamic_cast<Label *>(widget);
    assert(target == &textBoxType);
    return dynamic_cast<TextBox *>(widget);
}

const void *caption(void *value) {
    return &static_cast<Widget *>(value)->caption;
}

const void *label(void *value) {
    return &static_cast<Label *>(value)->text;
}

const void *textBox(void *value) {
    return &static_cast<TextBox *>(value)->text;
}
} // namespace

int main() {
    Symbols symbols{};
    symbols.address[DynamicCast] = reinterpret_cast<uintptr_t>(cast);
    symbols.address[WidgetType] = reinterpret_cast<uintptr_t>(&widgetType);
    symbols.address[LabelType] = reinterpret_cast<uintptr_t>(&labelType);
    symbols.address[TextBoxType] = reinterpret_cast<uintptr_t>(&textBoxType);
    symbols.address[Text] = reinterpret_cast<uintptr_t>(caption);
    symbols.address[LabelText] = reinterpret_cast<uintptr_t>(label);
    symbols.address[TextBoxText] = reinterpret_cast<uintptr_t>(textBox);
    Widget widget;
    Label plain;
    RichLabel rich;
    TextBox editable;
    assert(readWidgetText(symbols, &widget) == &widget.caption);
    assert(readWidgetText(symbols, static_cast<Widget *>(&plain)) == &plain.text);
    assert(static_cast<void *>(static_cast<Widget *>(&rich)) != static_cast<void *>(&rich));
    assert(readWidgetText(symbols, static_cast<Widget *>(&rich)) == &rich.text);
    assert(readWidgetText(symbols, static_cast<Widget *>(&editable)) == &editable.text);
}
