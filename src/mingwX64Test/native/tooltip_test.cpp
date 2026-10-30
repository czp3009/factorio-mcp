#include "widget_properties.h"
#include <cassert>
#include <cstddef>
#include <string>

struct Provider {
    unsigned char padding[57]{};
    std::string text, title;
};
struct Widget {
    unsigned char padding[91]{};
    void *creator{};
};
static Provider *plain;

static void *cast(void *value, long, void *, void *, int) {
    return value == plain ? plain : nullptr;
}

static const char *data(const void *value) {
    return static_cast<const std::string *>(value)->data();
}

static size_t size(const void *value) {
    return static_cast<const std::string *>(value)->size();
}

int main() {
    Symbols symbols{};
    symbols.address[DynamicCast] = reinterpret_cast<uintptr_t>(cast);
    symbols.address[StringData] = reinterpret_cast<uintptr_t>(data);
    symbols.address[StringSize] = reinterpret_cast<uintptr_t>(size);
    symbols.tooltips = {1, 2, offsetof(Widget, creator), offsetof(Provider, title), offsetof(Provider, text)};
    ObservationStorage storage;
    Widget widget;
    FmNode absent{};
    collectTooltip(symbols, &widget, absent, storage);
    assert(absent.tooltipStatus == 0);
    int dynamic;
    widget.creator = &dynamic;
    FmNode unsupported{};
    collectTooltip(symbols, &widget, unsupported, storage);
    assert(unsupported.tooltipStatus == 2);
    Provider provider;
    plain = &provider;
    widget.creator = &provider;
    provider.title = "[item=iron-plate] title";
    provider.text = "line one\nline two";
    FmNode observed{};
    collectTooltip(symbols, &widget, observed, storage);
    assert(observed.tooltipStatus == 3 && observed.tooltipTruncated == 0);
    assert(observationText(observed.tooltipTitle) == provider.title && observationText(observed.tooltipText) == provider.text);
    provider.title = "changed";
    assert(observationText(observed.tooltipTitle) == "[item=iron-plate] title");
    provider.text.assign(FM_OBSERVATION_TEXT_BYTES + 9, 'x');
    FmNode bounded{};
    collectTooltip(symbols, &widget, bounded, storage);
    assert(bounded.tooltipTruncated == 2 && bounded.tooltipText.size == FM_OBSERVATION_TEXT_BYTES);
    symbols.tooltips = {};
    FmNode unavailable{};
    collectTooltip(symbols, &widget, unavailable, storage);
    assert(unavailable.tooltipStatus == 0);
}
