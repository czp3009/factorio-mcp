#include <cstddef>
#include <cstdint>
#include <typeinfo>

struct Targetable {
    uintptr_t head;
};

struct Primary {
    virtual ~Primary() = default;
    uintptr_t padding[FIXTURE_PADDING];
};

struct Widget : Primary, Targetable {
    uintptr_t tail;
};

struct Derived : Widget {};
struct Private : private Targetable {};
struct Virtual : virtual Targetable { virtual ~Virtual() = default; };

extern "C" {
const std::type_info *fixture_classes[] = {&typeid(Targetable), &typeid(Primary), &typeid(Widget),
    &typeid(Derived), &typeid(Private), &typeid(Virtual)};
size_t fixture_widget_size = sizeof(Widget);
size_t fixture_base_size = sizeof(Targetable);
size_t fixture_base_offset = __builtin_offsetof(Widget, head);
}

int main() {
    Widget widget;
    return reinterpret_cast<const char *>(static_cast<Targetable *>(&widget)) -
        reinterpret_cast<const char *>(&widget) != static_cast<ptrdiff_t>(fixture_base_offset);
}
