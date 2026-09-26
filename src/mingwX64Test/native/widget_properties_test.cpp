#include "widget_properties.h"
#include <cassert>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

namespace {
struct OptionButton {
    uint64_t prefix{};
    std::string text;
};

struct OptionEntry {
    uint64_t prefix{};
    OptionButton *button{};
};

struct Fixture {
    unsigned char prefix[37]{};
    int32_t checked{};
    bool toggleMode{}, toggled{};
    int32_t selected{-1};
    double value{25.5}, minimum{-100}, maximum{100}, step{0.5};
    OptionEntry *first{}, *last{};
};

Fixture object;
int widgetType, toggleType, buttonType, dropdownType, sliderType;
void *recognized{};

void *cast(void *widget, long adjustment, void *source, void *target, int reference) {
    assert(widget == object.prefix && adjustment == 0 && source == &widgetType && reference == 0);
    return target == recognized ? &object : nullptr;
}

size_t stringSize(const void *value) {
    return static_cast<const std::string *>(value)->size();
}

const char *stringData(const void *value) {
    return static_cast<const std::string *>(value)->data();
}
} // namespace

int main() {
    Symbols symbols{};
    symbols.address[DynamicCast] = reinterpret_cast<uint64_t>(&cast);
    symbols.address[WidgetType] = reinterpret_cast<uint64_t>(&widgetType);
    symbols.address[ToggleButtonType] = reinterpret_cast<uint64_t>(&toggleType);
    symbols.address[ButtonType] = reinterpret_cast<uint64_t>(&buttonType);
    symbols.address[DropDownType] = reinterpret_cast<uint64_t>(&dropdownType);
    symbols.address[SliderType] = reinterpret_cast<uint64_t>(&sliderType);
    symbols.properties = {1,
                          offsetof(Fixture, checked),
                          offsetof(Fixture, toggleMode),
                          offsetof(Fixture, toggled),
                          offsetof(Fixture, selected),
                          offsetof(Fixture, value),
                          offsetof(Fixture, minimum),
                          offsetof(Fixture, maximum),
                          offsetof(Fixture, step)};
    auto collect = [&] {
        FmNode node{};
        collectWidgetProperties(symbols, object.prefix, node);
        return node;
    };
    assert(collect().properties == 0);
    recognized = &toggleType;
    object.checked = 2;
    assert(collect().properties == 1 && collect().checkState == 2);
    recognized = &buttonType;
    assert(collect().properties == 0);
    object.toggleMode = true;
    assert(collect().properties == 2 && !collect().toggled);
    object.toggled = true;
    assert(collect().toggled == 1);
    recognized = &dropdownType;
    assert(collect().properties == 4 && collect().selectedIndex == -1);
    object.selected = 2;
    assert(collect().selectedIndex == 2);
    auto result = std::make_unique<FmResult>();
    auto &layout = symbols.properties;
    layout.optionsSupported = 1;
    layout.optionFirst = offsetof(Fixture, first);
    layout.optionLast = offsetof(Fixture, last);
    layout.optionStride = sizeof(OptionEntry);
    layout.optionButton = offsetof(OptionEntry, button);
    layout.optionText = offsetof(OptionButton, text);
    symbols.address[StringSize] = reinterpret_cast<uint64_t>(&stringSize);
    symbols.address[StringData] = reinterpret_cast<uint64_t>(&stringData);
    FmNode options{};
    collectWidgetProperties(symbols, object.prefix, options, result.get());
    assert(options.properties == 20 && !options.optionTotal && !options.optionCount);
    OptionButton empty{}, unicode{0, std::string(510, 'x') + "\xe4\xb8\xad"};
    std::vector<OptionEntry> entries(70, {0, &unicode});
    entries[0].button = &empty;
    object.first = entries.data();
    object.last = entries.data() + entries.size();
    collectWidgetProperties(symbols, object.prefix, options, result.get());
    assert(options.optionTotal == 70 && options.optionCount == FM_MAX_WIDGET_OPTIONS && options.optionFirst == 0);
    assert(!result->options[0].text[0] && !result->options[0].truncated);
    assert(strlen(result->options[1].text) == 510 && result->options[1].truncated);
    result->optionCount = FM_MAX_OPTIONS - 1;
    collectWidgetProperties(symbols, object.prefix, options, result.get());
    assert(options.optionFirst == FM_MAX_OPTIONS - 1 && options.optionCount == 1);
    collectWidgetProperties(symbols, object.prefix, options, result.get());
    assert(!options.optionCount && options.optionTotal == 70);
    object.last = reinterpret_cast<OptionEntry *>(reinterpret_cast<uintptr_t>(object.first) + 1);
    bool rejected = false;
    try {
        collectWidgetProperties(symbols, object.prefix, options, result.get());
    } catch (const std::exception &) {
        rejected = true;
    }
    assert(rejected);
    recognized = &sliderType;
    auto slider = collect();
    assert(slider.properties == 8 && slider.value == 25.5 && slider.minimum == -100 && slider.maximum == 100 &&
           slider.valueStep == 0.5);
    symbols.properties.supported = 0;
    assert(collect().properties == 0);
    puts("Widget property extraction passed");
}
