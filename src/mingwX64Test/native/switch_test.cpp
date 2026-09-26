#include "widget_properties.h"
#include <array>
#include <cassert>

namespace {
int widget, widgetType, switchType;
std::array<unsigned char, 128> receiver{};
bool present = true;

void *cast(void *value, long adjustment, void *source, void *target, int reference) {
    assert(value == &widget && adjustment == 0 && source == &widgetType && target == &switchType && reference == 0);
    return present ? receiver.data() : nullptr;
}
} // namespace

int main() {
    Symbols symbols{};
    symbols.address[DynamicCast] = reinterpret_cast<uintptr_t>(&cast);
    symbols.address[WidgetType] = reinterpret_cast<uintptr_t>(&widgetType);
    auto &layout = symbols.switches;
    layout.type = reinterpret_cast<uintptr_t>(&switchType);
    auto collect = [&] {
        FmNode node{};
        collectSwitch(symbols, &widget, node);
        return node;
    };
    assert(collect().properties == 0);
    layout.supported = 1;
    for (unsigned shift : {0u, 32u}) {
        layout.state = shift + 5;
        layout.allowNone = shift + 17;
        for (unsigned value : {0u, 1u, 2u, 253u}) {
            receiver[layout.state] = static_cast<unsigned char>(value);
            for (bool allowNone : {false, true}) {
                receiver[layout.allowNone] = allowNone;
                auto node = collect();
                assert(node.properties == 8192 && node.switchState == value && node.switchAllowNone == allowNone);
            }
        }
    }
    present = false;
    assert(collect().properties == 0);
}
