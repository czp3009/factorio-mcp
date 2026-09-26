#include "widget_properties.h"
#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <limits>
#include <stdexcept>

namespace {
struct Receiver {
    uintptr_t *table;
    double count;
    bool draw, zero, unknown, infinite;
};

Receiver receiver{};
int widget, widgetType, numberType, calls;
bool present{true};

void *cast(void *value, long, void *source, void *target, int) {
    assert(value == &widget && source == &widgetType && target == &numberType);
    return present ? &receiver : nullptr;
}

double count(const void *self) {
    assert(self == &receiver);
    ++calls;
    return receiver.count;
}

bool draw(const void *self) {
    assert(self == &receiver);
    return receiver.draw;
}

bool zero(const void *self) {
    assert(self == &receiver && receiver.draw);
    return receiver.zero;
}

bool unknown(const void *self) {
    assert(self == &receiver && receiver.draw);
    return receiver.unknown;
}

bool infinite(const void *self) {
    assert(self == &receiver && receiver.draw);
    return receiver.infinite;
}
} // namespace

int main() {
    Symbols symbols{};
    symbols.address[DynamicCast] = reinterpret_cast<uintptr_t>(&cast);
    symbols.address[WidgetType] = reinterpret_cast<uintptr_t>(&widgetType);
    symbols.numbers.type = reinterpret_cast<uintptr_t>(&numberType);
    auto collect = [&] {
        FmNode node{};
        collectNumber(symbols, &widget, node);
        return node;
    };
    assert(collect().properties == 0);
    symbols.numbers.supported = 1;
    std::array<uintptr_t, 10> table{};
    receiver.table = table.data();
    for (unsigned shift : {0u, 3u}) {
        symbols.numbers.count = (shift + 4) * sizeof(void *);
        symbols.numbers.draw = (shift + 1) * sizeof(void *);
        symbols.numbers.zero = (shift + 3) * sizeof(void *);
        symbols.numbers.unknown = (shift + 0) * sizeof(void *);
        symbols.numbers.infinite = (shift + 2) * sizeof(void *);
        table[shift + 4] = reinterpret_cast<uintptr_t>(&count);
        table[shift + 1] = reinterpret_cast<uintptr_t>(&draw);
        table[shift + 3] = reinterpret_cast<uintptr_t>(&zero);
        table[shift + 0] = reinterpret_cast<uintptr_t>(&unknown);
        table[shift + 2] = reinterpret_cast<uintptr_t>(&infinite);
        receiver = {table.data(), 42.5, true, true, false, false};
        auto node = collect();
        assert(node.properties == 64 && node.numberFlags == 19 && node.numberValue == 42.5);
        int before = calls;
        receiver.unknown = true;
        assert(collect().numberFlags == 7 && calls == before);
        receiver.unknown = false;
        receiver.infinite = true;
        assert(collect().numberFlags == 11 && calls == before);
        receiver.draw = false;
        assert(collect().numberFlags == 0 && calls == before);
        present = false;
        assert(collect().properties == 0);
        present = true;
        receiver = {table.data(), std::numeric_limits<double>::quiet_NaN(), true, false, false, false};
        assert(std::isnan(collect().numberValue));
    }
    symbols.numbers.draw = 3;
    bool rejected = false;
    try {
        collect();
    } catch (const std::runtime_error &) {
        rejected = true;
    }
    assert(rejected);
}
