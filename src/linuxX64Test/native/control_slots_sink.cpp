#include <cassert>

extern "C" __attribute__((noinline)) int fixture_stick_values(const int* begin, const int* end, int code) {
    if (code == 1) {
        assert(end - begin == 2);
        return begin[0];
    }
    if (code == 2) {
        assert(end - begin == 2);
        return begin[1];
    }
    return 0;
}
