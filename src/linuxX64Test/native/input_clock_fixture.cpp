#include <cmath>
#include <cstdint>
#include <sys/time.h>

extern "C" {
unsigned char fixture_ticks_started = 0;
timeval fixture_origin{};
}

extern "C" __attribute__((noinline)) double fixture_input_time() {
    if (!fixture_ticks_started) {
        fixture_ticks_started = 1;
        gettimeofday(&fixture_origin, nullptr);
    }
    timeval current;
    gettimeofday(&current, nullptr);
    const std::uint32_t seconds = static_cast<std::uint32_t>(current.tv_sec - fixture_origin.tv_sec);
    const auto micros = current.tv_usec - fixture_origin.tv_usec;
    const std::uint32_t ticks = seconds * 1000 + micros / 1000;
    return static_cast<double>(ticks) / FIXTURE_DIVISOR;
}

int main() {
    const auto first = fixture_input_time();
    const auto second = fixture_input_time();
    return fixture_ticks_started && std::isfinite(first) && first >= 0 && std::isfinite(second) && second >= 0 ? 0 : 1;
}
