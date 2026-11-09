#include "ui_toggle.h"
#include <cassert>
#include <cstddef>
#include <cstdio>
#include <typeinfo>

struct Widget {
    virtual ~Widget() = default;
};

struct Prefix {
    virtual ~Prefix() = default;
    uint64_t padding[5]{};
};

struct Button : Prefix, Widget {
    uint8_t toggled = 1, mode = 0;
    virtual bool isToggled() const {
        assert(false);
        return false;
    }
};

struct DerivedPrefix {
    virtual ~DerivedPrefix() = default;
    uint64_t padding[3]{};
};

struct Derived : DerivedPrefix, Button {
    bool isToggled() const override {
        assert(false);
        return false;
    }
};

int main() {
    Button button;
    const auto start = reinterpret_cast<uintptr_t>(&button);
    FmLinuxToggleLayout layout{reinterpret_cast<uintptr_t>(&typeid(Widget)),
        reinterpret_cast<uintptr_t>(&typeid(Button)), sizeof(Button),
        static_cast<uint32_t>(reinterpret_cast<uintptr_t>(&button.toggled) - start),
        static_cast<uint32_t>(reinterpret_cast<uintptr_t>(&button.mode) - start)};
    assert(validToggleLayout(layout));
    uint32_t available = 0, value = 0, mode = 0;
    Widget *widget = &button;
    assert(reinterpret_cast<uintptr_t>(widget) != start);
    assert(readToggle(reinterpret_cast<uintptr_t>(widget), layout, available, value, mode) == 0);
    assert(available && value == 1 && mode == 0);
    Derived derived;
    derived.toggled = 0;
    derived.mode = 1;
    widget = &derived;
    assert(reinterpret_cast<uintptr_t>(static_cast<Button *>(&derived)) != reinterpret_cast<uintptr_t>(&derived));
    assert(readToggle(reinterpret_cast<uintptr_t>(widget), layout, available, value, mode) == 0);
    assert(available && value == 0 && mode == 1);
    Widget unrelated;
    assert(readToggle(reinterpret_cast<uintptr_t>(&unrelated), layout, available, value, mode) == 0 && !available);
    derived.toggled = 2;
    assert(readToggle(reinterpret_cast<uintptr_t>(widget), layout, available, value, mode) == EPROTO && !available);
    derived.toggled = 0;
    derived.mode = 2;
    assert(readToggle(reinterpret_cast<uintptr_t>(widget), layout, available, value, mode) == EPROTO && !available);
    auto invalid = layout;
    invalid.offset = invalid.objectSize;
    assert(!validToggleLayout(invalid));
    invalid = layout;
    invalid.buttonType = invalid.widgetType;
    assert(!validToggleLayout(invalid));
    assert(readToggle(reinterpret_cast<uintptr_t>(widget), {}, available, value, mode) == 0 && !available);
    std::puts("Stored button flags preserve adjusted receivers and avoid virtual callbacks");
}
