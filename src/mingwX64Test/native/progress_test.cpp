#include "widget_properties.h"
#include <cassert>
#include <cstddef>
#include <cstring>
#include <limits>
#include <array>

namespace {
int widget, widgetType, progressType;
std::array<unsigned char, 128> receiver{};
bool present = true;

void *cast(void *value, long, void *source, void *target, int) {
    assert(value == &widget && source == &widgetType && target == &progressType);
    return present ? receiver.data() : nullptr;
}
} // namespace

int main() {
    Symbols symbols{};
    symbols.address[DynamicCast] = reinterpret_cast<uintptr_t>(&cast);
    symbols.address[WidgetType] = reinterpret_cast<uintptr_t>(&widgetType);
    auto &layout = symbols.progress;
    layout.type = reinterpret_cast<uintptr_t>(&progressType);
    auto collect = [&] {
        FmNode node{};
        collectProgress(symbols, &widget, node);
        return node;
    };
    assert(collect().properties == 0);
    layout.supported = 1;
    for (unsigned shift : {0u, 32u}) {
        layout.value = shift + 3;
        layout.direction = shift + 19;
        layout.hasText = shift + 24;
        for (double value : {-0.25, 0.375, 2.5, std::numeric_limits<double>::infinity()}) {
            memcpy(receiver.data() + layout.value, &value, sizeof(value));
            receiver[layout.direction] = 231;
            receiver[layout.hasText] = 0;
            auto node = collect();
            assert(node.properties == 256 && node.progressValue == value);
            assert(node.progressDirection == 231 && !node.progressHasText);
            receiver[layout.hasText] = 1;
            assert(collect().progressHasText == 1);
        }
    }
    present = false;
    assert(collect().properties == 0);
}
