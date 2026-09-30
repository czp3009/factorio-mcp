#include "pointer_member_fixture.h"
#include <cassert>
#include <cstddef>

struct Prototype {
    std::uint64_t padding[FIXTURE_PADDING];
    Text name;
    std::uint64_t gap[FIXTURE_PADDING];
    Text description;
};

struct Wrapper {
    std::uint64_t padding[FIXTURE_PADDING + 1];
    Prototype* prototype;

    __attribute__((noinline)) int read(State* state) const {
        fixture_push(state, &prototype->name);
        return 1;
    }

    __attribute__((noinline)) int description(State* state) const {
        fixture_push(state, &prototype->description);
        return 1;
    }
};

struct String {
    std::uint64_t storage[3];
};

struct Control {
    std::uint64_t padding[FIXTURE_PADDING + 2];
    Prototype* prototype;

    __attribute__((noinline)) String name() const {
        if (prototype) {
            prototype->name.update();
            return {{1, 2, 3}};
        }
        return {{0, 0, 0}};
    }
};

extern "C" {
extern const std::size_t fixture_wrapper_size = sizeof(Wrapper);
extern const std::size_t fixture_control_size = sizeof(Control);
extern const std::size_t fixture_prototype_size = sizeof(Prototype);
extern const std::size_t fixture_wrapper_pointer = offsetof(Wrapper, prototype);
extern const std::size_t fixture_control_pointer = offsetof(Control, prototype);
extern const std::size_t fixture_name_member = offsetof(Prototype, name);
extern const std::size_t fixture_description_member = offsetof(Prototype, description);
}

int main() {
    Prototype prototype{};
    Wrapper wrapper{};
    wrapper.prototype = &prototype;
    State state{};
    assert(wrapper.read(&state) == 1 && state.value == &prototype.name);
    assert(wrapper.description(&state) == 1 && state.value == &prototype.description);
    Control control{};
    assert(control.name().storage[0] == 0 && prototype.name.calls == 0);
    control.prototype = &prototype;
    assert(control.name().storage[0] == 1 && prototype.name.calls == 1);
}
