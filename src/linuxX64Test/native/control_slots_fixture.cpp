#include <cassert>
#include <cstddef>
#include <cstdint>
#include <utility>

struct Binding {
    std::uint64_t padding[FIXTURE_PADDING];
    unsigned char type;
    int code;
    std::uint64_t trailing;
};

struct Options {
    std::uint64_t padding[FIXTURE_PADDING];
    unsigned char method;
};

struct Global {
    std::uint64_t padding[FIXTURE_PADDING];
    Options* options;
};

extern "C" Global* fixture_global;
extern "C" int fixture_stick_values(const int* begin, const int* end, int code);

struct Control {
    std::uint64_t padding[FIXTURE_PADDING];
    Binding controller1;
    Binding keyboard1;
    Binding controller2;
    Binding keyboard2;

    __attribute__((always_inline)) std::pair<const Binding*, const Binding*> activeValues() const {
        if (fixture_global->options->method == 0) return {&keyboard1, &keyboard2};
        return {&controller1, &controller2};
    }

    __attribute__((noinline)) bool hasKey(int code) const {
        auto values = activeValues();
        if (values.first->type == 19 && values.first->code == code) return true;
        return values.second->type == 19 && values.second->code == code;
    }

    __attribute__((noinline)) int sticks() const {
        static const int values[]{10, 20};
        int first = fixture_stick_values(values, values + 2, controller1.code);
        int second = fixture_stick_values(values, values + 2, controller2.code);
        return first + second;
    }
};

extern "C" {
Global* fixture_global;
extern const std::size_t fixture_control_size = sizeof(Control);
extern const std::size_t fixture_control_slots[]{offsetof(Control, keyboard1), offsetof(Control, keyboard2),
    offsetof(Control, controller1), offsetof(Control, controller2)};
extern const std::size_t fixture_binding_type = offsetof(Binding, type);
extern const std::size_t fixture_binding_code = offsetof(Binding, code);
}

int main() {
    Options options{};
    Global global{};
    global.options = &options;
    fixture_global = &global;
    Control control{};
    control.keyboard1.type = control.keyboard2.type = 19;
    control.keyboard1.code = 21;
    control.keyboard2.code = 31;
    control.controller1.code = 1;
    control.controller2.code = 2;
    assert(control.hasKey(21));
    assert(control.hasKey(31));
    assert(!control.hasKey(41));
    options.method = 1;
    assert(!control.hasKey(21));
    control.controller1.type = 19;
    assert(control.hasKey(1));
    assert(control.sticks() == 30);
}
