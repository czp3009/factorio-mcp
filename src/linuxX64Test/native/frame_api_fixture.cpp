#include "frame_api.h"
#include <array>
#include <cassert>
#include <cerrno>

namespace {
unsigned calls;
void integer(uint32_t, int32_t *) { ++calls; }
void binding(uint32_t, uint32_t) { ++calls; }
void store(uint32_t, int32_t) { ++calls; }
void pixels(int32_t, int32_t, int32_t, int32_t, uint32_t, uint32_t, void *) { ++calls; }
uint32_t error() { ++calls; return 123; }
} // namespace

int main() {
    const auto address = [](auto *value) { return reinterpret_cast<uintptr_t>(value); };
    std::array<uintptr_t, 6> slots{address(integer), address(binding), address(binding),
        address(store), address(pixels), address(error)};
    const auto entry = [&](size_t index) { return FmLinuxFrameApiEntry{address(&slots[index]), slots[index]}; };
    const FmLinuxFrameApiConfig config{entry(0), entry(1), entry(2), entry(3), entry(4), entry(5)};
    FrameReadbackApi api{};
    assert(readFrameApi(config, api) == 0 && calls == 0);
    assert(api.getInteger == integer && api.bindFramebuffer == binding && api.bindBuffer == binding &&
        api.pixelStore == store && api.readPixels == pixels && api.getError == error);
    assert(api.getError() == 123 && calls == 1);
    const auto clear = [&] {
        assert(!api.getInteger && !api.bindFramebuffer && !api.bindBuffer && !api.pixelStore &&
            !api.readPixels && !api.getError);
    };
    for (size_t index = 0; index < slots.size(); ++index) {
        const auto original = slots[index];
        slots[index] = 0;
        assert(readFrameApi(config, api) == ESTALE);
        clear();
        slots[index] = original;
        assert(readFrameApi(config, api) == 0);
    }
    auto invalid = config;
    invalid.getError.storage = config.getInteger.storage;
    assert(readFrameApi(invalid, api) == EINVAL);
    clear();
    invalid = config;
    invalid.readPixels.storage = 8;
    assert(readFrameApi(invalid, api) == EFAULT);
    clear();
    invalid = config;
    invalid.pixelStore.function = 0;
    assert(readFrameApi(invalid, api) == EINVAL);
    clear();
    assert(calls == 1); // Resolving/rejecting bindings never invokes a graphics entry.
}
