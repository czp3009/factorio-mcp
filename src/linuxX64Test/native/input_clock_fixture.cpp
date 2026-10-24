#include <cassert>
#include <cmath>
#include <cstdint>

extern "C" __attribute__((noinline)) std::uint64_t fixture_clock() {
    static std::uint64_t value = 10000000000;
    return ++value;
}

extern "C" {
std::uint64_t (*fixture_ticks)() = fixture_clock;
}

extern "C" __attribute__((noinline)) double fixture_input_time() {
    return static_cast<double>(fixture_ticks()) / FIXTURE_DIVISOR;
}

int main() {
    const auto first = fixture_input_time();
    const auto second = fixture_input_time();
    assert(std::isfinite(first) && first > 0 && second >= first);
}
