#include <cassert>
#include <cstddef>
#include <cstdint>

struct Control {
    std::uint64_t padding[FIXTURE_PADDING];
    const Control* linked;

    __attribute__((noinline)) const Control* direct() const {
        const Control* current = this;
        const Control* owner;
        do {
            owner = current;
            current = current->linked;
        } while (current);
        return owner;
    }

    __attribute__((noinline)) const Control* guarded() const {
        const Control* current = this;
        while (current->linked) current = current->linked;
        return current;
    }
};

extern "C" {
extern const std::size_t fixture_control_extent = sizeof(Control);
extern const std::size_t fixture_control_link = offsetof(Control, linked);
}

int main() {
    Control first{};
    Control second{};
    Control third{};
    assert(first.direct() == &first);
    assert(first.guarded() == &first);
    first.linked = &second;
    assert(first.direct() == &second);
    assert(first.guarded() == &second);
    second.linked = &third;
    assert(first.direct() == &third);
    assert(first.guarded() == &third);
    assert(second.direct() == &third);
    assert(second.guarded() == &third);
}
