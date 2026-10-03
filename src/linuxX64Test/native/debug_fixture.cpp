#include <cstddef>
#include <cstdint>
#include <cstdlib>

namespace fixture {
enum class Mode : std::uint16_t { Ready = 7, Busy = 19 };

struct Target {
    std::uint64_t identity;
};

struct Widget {
    virtual ~Widget() = default;
    virtual Target *target() = 0;
    virtual std::uint32_t count() const = 0;
    bool enabled = true;
    std::uint16_t index = 17;
    Target *value;
    Mode mode = Mode::Ready;
};

struct Concrete : Widget {
    Target *target() override {
        return value;
    }

    std::uint32_t count() const override {
        return index;
    }
};

struct Side {
    virtual ~Side() = default;

    virtual unsigned side() {
        return 3;
    }
};

struct Combined : Concrete, Side {};

Combined combined;

Target target{42};
Concrete widget;
} // namespace fixture

// These independent compiler values are compared with the DWARF reader in Kotlin tests.
extern "C" {
std::uint64_t fixture_widget_size = sizeof(fixture::Widget);
std::uint64_t fixture_enabled_offset = __builtin_offsetof(fixture::Widget, enabled);
std::uint64_t fixture_index_offset = __builtin_offsetof(fixture::Widget, index);
std::uint64_t fixture_value_offset = __builtin_offsetof(fixture::Widget, value);

__attribute__((noinline)) int fixture_function(int value) {
    return value + 17;
}

[[noreturn]] __attribute__((noinline)) void fixture_never_return() {
    std::abort();
}

__attribute__((noinline)) void fixture_abort_wrapper() {
    fixture_never_return();
}
}

int main() {
    fixture::widget.value = &fixture::target;
    return fixture_function(fixture::widget.count()) == 34 && fixture::widget.target()->identity == 42 &&
                   fixture::widget.mode == fixture::Mode::Ready
               ? 0
               : 1;
}
