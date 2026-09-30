#include <cassert>
#include <cstddef>
#include <cstdint>

enum class Continuous : unsigned { Equal = FIXTURE_PADDING + 3, Different = FIXTURE_PADDING + 9 };
constexpr unsigned comparison = FIXTURE_PADDING * 7;
extern "C" bool fixture_required();
extern "C" void fixture_noise();

struct Value {
    volatile bool down;

    __attribute__((noinline)) bool modifiers(Continuous continuous) const {
        volatile Continuous saved = continuous;
        return fixture_required() && saved == Continuous::Equal;
    }

    __attribute__((noinline)) bool active(bool gui, Continuous continuous) const {
        volatile Continuous saved = continuous;
        if (gui) fixture_noise();
        return modifiers(saved) && down;
    }
};

struct Control {
    std::uint64_t padding[FIXTURE_PADDING];
    Control* linked;
    std::uint64_t more[FIXTURE_PADDING + 1];
    unsigned usage;
    Value first;
    Value second;

    __attribute__((noinline)) unsigned active() const {
        const Control* owner = this;
        while (owner->linked) owner = owner->linked;
        const Continuous continuous = owner->usage == comparison ? Continuous::Equal : Continuous::Different;
        if (owner->first.active(true, continuous)) return 1;
        return owner->second.active(false, continuous) ? 2 : 0;
    }
};

extern "C" {
extern const std::size_t fixture_control_size = sizeof(Control);
extern const std::size_t fixture_usage_field = offsetof(Control, usage);
extern const unsigned fixture_usage_comparison = comparison;
extern const unsigned fixture_usage_equal = static_cast<unsigned>(Continuous::Equal);
extern const unsigned fixture_usage_different = static_cast<unsigned>(Continuous::Different);
}

int main() {
    Control root{};
    Control owner{};
    root.linked = &owner;
    owner.first.down = false;
    owner.second.down = true;
    for (unsigned raw = 0; raw < 512; ++raw) {
        owner.usage = raw;
        const unsigned expected = raw == comparison ? 2 : 0;
        assert(root.active() == expected);
        assert(owner.active() == expected);
    }
    owner.usage = comparison;
    owner.first.down = true;
    assert(root.active() == 1);
}
