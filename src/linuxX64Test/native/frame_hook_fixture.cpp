#include "frame_hook.h"
#include "gui_hook.h"
#include "pointer_hook.h"
#include <array>
#include <cassert>
#include <cfenv>
#include <execinfo.h>
#include <stdexcept>
#include <sys/mman.h>
#include <unistd.h>

struct Returned { uint64_t integer; double floating; };
extern "C" {
uint64_t fm_frame_original = 0;
unsigned char fm_fixture_frame_carry = 0;
Returned fm_fixture_frame_invoke(void *, void *, uintptr_t);
void fm_fixture_frame_return();
void fm_fixture_frame_original();
}

namespace {
unsigned observed, forwarded;
bool fail, unwound;
void *device = reinterpret_cast<void *>(0x1000);
void *window = reinterpret_cast<void *>(0x2000);

__attribute__((noinline)) void dirtyStack() {
    volatile unsigned char bytes[70000];
    for (size_t index = 0; index < sizeof(bytes); ++index)
        bytes[index] = 0xa5;
}

void vectors() {
    if (fm_xsave_mode && (fm_xsave_low & 4)) {
        alignas(32) const uint64_t values[4] = {~uint64_t{0}, ~uint64_t{0}, ~uint64_t{0}, ~uint64_t{0}};
        asm volatile("vmovups %0, %%ymm15" : : "m"(values) : "ymm15");
    } else {
        asm volatile("pcmpeqd %%xmm15, %%xmm15" ::: "xmm15");
    }
}
} // namespace

extern "C" Returned fm_fixture_frame_body(void *actualDevice, void *actualWindow, uint64_t a, uint64_t b,
                                          uint64_t c, uint64_t d, uint64_t e, uint64_t f, double number) {
    std::array<unsigned char, 32> vector;
    if (fm_xsave_mode && (fm_xsave_low & 4))
        asm volatile("vmovups %%ymm15, %0" : "=m"(vector));
    else
        asm volatile("movdqu %%xmm15, %0" : "=m"(vector));
    for (size_t index = 0; index < (fm_xsave_mode && (fm_xsave_low & 4) ? 32u : 16u); ++index)
        assert(vector[index] == 255);
    assert(actualDevice == device && actualWindow == window && fm_fixture_frame_carry == 1);
    assert(a == 11 && b == 22 && c == 33 && d == 44 && e == 55 && f == 66 && number == 3.25);
    assert(std::fegetround() == FE_TONEAREST && observed == forwarded + 1 && unwound);
    ++forwarded;
    if (fail)
        throw std::runtime_error("native presentation exception");
    return {a + b + c + d + e + f, number};
}

extern "C" void fm_before_frame(void *actualDevice, void *actualWindow, uintptr_t caller) noexcept {
    assert(actualDevice == device && actualWindow == window &&
        caller == reinterpret_cast<uintptr_t>(&fm_fixture_frame_return));
    ++observed;
    void *frames[16];
    unwound = backtrace(frames, 16) >= 5;
    std::fesetround(FE_DOWNWARD);
    asm volatile("clc\npxor %%xmm0, %%xmm0\npxor %%xmm15, %%xmm15" ::: "cc", "xmm0", "xmm15");
    if (fm_xsave_mode && (fm_xsave_low & 4))
        asm volatile("vxorps %%ymm15, %%ymm15, %%ymm15" ::: "ymm15");
}

int main() {
    alarm(30);
    assert(initializeHookState());
    const auto available = fm_xsave_mode;
    const auto pageSize = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    auto *entry = static_cast<uintptr_t *>(mmap(nullptr, pageSize, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    assert(entry != MAP_FAILED);
    *entry = fm_frame_original = reinterpret_cast<uintptr_t>(&fm_fixture_frame_original);
    assert(mprotect(entry, pageSize, PROT_READ) == 0);
    PointerHook hook(mprotect);
    for (uint32_t mode = 0; mode <= available; ++mode) {
        fm_xsave_mode = mode;
        assert(hook.install(reinterpret_cast<uintptr_t>(entry), fm_frame_original,
            reinterpret_cast<uintptr_t>(&fm_frame_hook), pageSize, PROT_READ) == 0);
        dirtyStack();
        vectors();
        const auto result = fm_fixture_frame_invoke(device, window, *entry);
        assert(result.integer == 231 && result.floating == 3.25 && observed == forwarded);
        fail = true;
        vectors();
        try {
            fm_fixture_frame_invoke(device, window, *entry);
            assert(false);
        } catch (const std::runtime_error &) {
            assert(observed == forwarded);
        }
        fail = false;
        assert(hook.remove() == 0 && !hook.hasOwnership() && *entry == fm_frame_original);
        assert(hook.remove() == 0);
    }
    assert(munmap(entry, pageSize) == 0);
}
