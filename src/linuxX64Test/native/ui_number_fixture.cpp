#include "ui_number.h"
#include <cassert>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <limits>
#include <typeinfo>

struct Widget {
    virtual ~Widget() = default;
    unsigned char padding[FIXTURE_PADDING + 1]{};
};

struct Number {
    virtual bool zero() const {
        return false;
    }

    virtual double count() const = 0;

    virtual bool unknown() const {
        return false;
    }

    virtual bool infinite() const {
        return false;
    }

    virtual bool draw() const {
        return true;
    }
};

struct Counted {
    bool drawn = true, showZero = true, unknownValue = false, infiniteValue = false;
    double value = -0.0;
    mutable unsigned countCalls = 0, flagCalls = 0;
};

struct Button : Widget, Number, Counted {
    double count() const override {
        ++countCalls;
        return value;
    }

    bool draw() const override {
        return drawn;
    }

    bool zero() const override {
        ++flagCalls;
        return showZero;
    }

    bool unknown() const override {
        ++flagCalls;
        return unknownValue;
    }

    bool infinite() const override {
        ++flagCalls;
        return infiniteValue;
    }
};

struct VirtualButton : Widget, virtual Number, Counted {
    double count() const override {
        ++countCalls;
        return value;
    }

    bool draw() const override {
        return drawn;
    }

    bool zero() const override {
        ++flagCalls;
        return showZero;
    }

    bool unknown() const override {
        ++flagCalls;
        return unknownValue;
    }

    bool infinite() const override {
        ++flagCalls;
        return infiniteValue;
    }
};

struct Left : Number {
    double count() const override {
        return 1;
    }
};

struct Right : Number {
    double count() const override {
        return 2;
    }
};

struct Ambiguous : Widget, Left, Right {};

static FmLinuxNumberLayout layout() {
    // These positions describe only this deliberately reordered synthetic interface.
    return {reinterpret_cast<uintptr_t>(&typeid(Widget)), reinterpret_cast<uintptr_t>(&typeid(Number)), 1, 4, 0, 2, 3};
}

template <class T> void check() {
    T button;
    Widget *widget = &button;
    assert(reinterpret_cast<void *>(widget) != static_cast<Number *>(&button));
    FmLinuxUiNode node{};
    auto observe = [&] {
        node = {};
        return collectNumber(reinterpret_cast<uintptr_t>(widget), layout(), node);
    };
    assert(observe() == 0 && node.numberAvailable && node.numberFlags == 19);
    assert(std::signbit(node.numberValue) && button.countCalls == 1 && button.flagCalls == 3);
    button.value = std::numeric_limits<double>::infinity();
    assert(observe() == 0 && std::isinf(node.numberValue));
    button.value = std::numeric_limits<double>::quiet_NaN();
    assert(observe() == 0 && std::isnan(node.numberValue));
    button.unknownValue = true;
    assert(observe() == 0 && node.numberFlags == 7 && button.countCalls == 3);
    button.unknownValue = false;
    button.infiniteValue = true;
    assert(observe() == 0 && node.numberFlags == 11 && button.countCalls == 3);
    button.drawn = false;
    const auto flags = button.flagCalls;
    assert(observe() == 0 && node.numberAvailable && !node.numberFlags);
    assert(button.flagCalls == flags && button.countCalls == 3);
}

int main() {
    check<Button>();
    check<VirtualButton>();
    Widget plain;
    Ambiguous ambiguous;
    FmLinuxUiNode node{};
    assert(collectNumber(reinterpret_cast<uintptr_t>(&plain), layout(), node) == 0 && !node.numberAvailable);
    assert(collectNumber(reinterpret_cast<uintptr_t>(static_cast<Widget *>(&ambiguous)), layout(), node) == 0 &&
           !node.numberAvailable);
    auto invalid = layout();
    invalid.count = invalid.draw;
    assert(!validNumberLayout(invalid));
    assert(collectNumber(reinterpret_cast<uintptr_t>(&plain), invalid, node) == EINVAL);
    invalid = layout();
    invalid.count = 256;
    assert(!validNumberLayout(invalid));
    assert(collectNumber(1, layout(), node) == EFAULT);
    assert(validNumberLayout({}));
    assert(collectNumber(0, {}, node) == 0);
}
