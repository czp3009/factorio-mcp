#include <cassert>
#include <cstddef>
#include <cstdint>

extern "C" bool fixture_in_gui();
extern "C" bool fixture_gui_active;

struct Value {
    bool collides;
    bool down;

    __attribute__((noinline)) bool active(bool gui) const {
        if (collides) {
            if (!gui) return false;
            if (!fixture_in_gui()) return false;
        }
        return down;
    }
};

constexpr unsigned shift = FIXTURE_PADDING % 5;

struct Control {
    std::uint64_t padding[FIXTURE_PADDING];
    Control* linked;
    std::uint64_t more[FIXTURE_PADDING + 1];
    unsigned char flags;
    Value first;
    Value second;

    __attribute__((noinline)) unsigned active() const {
        const Control* owner = this;
        while (owner->linked) owner = owner->linked;
        const bool gui = (owner->flags >> shift) & 1;
        if (owner->first.active(gui)) return 1;
        return owner->second.active(gui) ? 2 : 0;
    }
};

extern "C" {
extern const std::size_t fixture_control_size = sizeof(Control);
extern const std::size_t fixture_gui_field = offsetof(Control, flags);
extern const unsigned fixture_gui_shift = shift;
}

int main() {
    Control root{};
    Control owner{};
    root.linked = &owner;
    owner.first = {true, false};
    owner.second = {true, true};
    for (unsigned raw = 0; raw < 256; ++raw) {
        owner.flags = raw;
        for (unsigned mode = 0; mode < 2; ++mode) {
            fixture_gui_active = mode;
            const unsigned expected = mode && ((raw >> shift) & 1) ? 2 : 0;
            assert(root.active() == expected);
            assert(owner.active() == expected);
        }
    }
    owner.first = {false, true};
    assert(root.active() == 1);
}
