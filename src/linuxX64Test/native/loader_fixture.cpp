#include <cstdint>

extern "C" uint64_t fm_loader_value(uint64_t value) {
    return value * 17 + 9;
}
