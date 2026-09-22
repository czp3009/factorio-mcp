#include "resident_hooks.h"
#include <cpuid.h>
#include <sys/mman.h>
#include <ucontext.h>
#include <unistd.h>
#include <cstring>
#include <cstdio>
#include <stdexcept>

struct Pair { double x, y; };
extern "C" uint64_t resident_integer_fixture(uint64_t, uint64_t);
extern "C" char resident_integer_fixture_end[];
extern "C" Pair resident_fixture(uint64_t *, double, double);
extern "C" char resident_fixture_end[], resident_fixture_branch[], resident_fixture_branch_end[];
extern "C" { uint64_t resident_xsave_mask = 0; }
namespace {
FrPatch patch{};
uintptr_t caller = 0;
unsigned entered = 0, left = 0;
bool replace_result = false;
bool integer_result = false;
void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}
void write_code(const unsigned char *bytes) {
    auto page = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
    auto base = patch.address & ~(page - 1);
    auto size = ((patch.address + patch.length + page - 1) & ~(page - 1)) - base;
    require(!mprotect(reinterpret_cast<void *>(base), size, PROT_READ | PROT_WRITE | PROT_EXEC), "Cannot open fixture code for patching");
    memcpy(reinterpret_cast<void *>(patch.address), bytes, patch.length);
    require(!mprotect(reinterpret_cast<void *>(base), size, PROT_READ | PROT_EXEC), "Cannot restore fixture code protection");
}
void clobber() {
    asm volatile(R"asm(
        pxor %%xmm0, %%xmm0
        pxor %%xmm1, %%xmm1
        pxor %%xmm2, %%xmm2
    )asm"
        ::: "xmm0", "xmm1", "xmm2", "memory");
}
void exercise() {
    uint64_t calls = 0;
    for (unsigned i = 0; i < 100; ++i) {
        replace_result = i % 2;
        auto result = resident_fixture(&calls, 2.0, 3.0);
        require(result.x == (replace_result ? 9.0 : 5.5) && result.y == 2.5, "Detour lost or mis-restored floating-point state");
        require(calls == i + 1, "Detour did not preserve the integer argument");
    }
}
}
extern "C" void resident_enter(FrFrame *frame, void *) noexcept {
    if (frame->branch != FR_CONTROL) __builtin_trap();
    frame->branch = patch.trampoline;
    caller = frame->return_address;
    frame->return_address = reinterpret_cast<uintptr_t>(resident_after);
    ++entered;
    clobber();
}
extern "C" void resident_leave(FrFrame *frame, void *xstate) noexcept {
    frame->branch = caller;
    if (integer_result) {
        // The game adapter changes only the boolean low byte after a real control evaluation.
        frame->rax=(frame->rax & ~uintptr_t(0xff)) | (replace_result ? 1 : 0);
    } else if (replace_result) {
        double value = 9.0;
        auto *fp = static_cast<_libc_fpstate *>(xstate);
        memcpy(&fp->_xmm[0], &value, sizeof value);
        if (resident_xsave_mask) *reinterpret_cast<uint64_t *>(static_cast<char *>(xstate) + 512) |= 2;
    }
    ++left;
    clobber();
}
int main() {
    bool patched = false;
    try {
        auto address = reinterpret_cast<uintptr_t>(resident_fixture);
        auto size = reinterpret_cast<uintptr_t>(resident_fixture_end) - address;
        resident_prepare_hook(patch, {address, size}, FR_CONTROL);
        write_code(patch.replacement);
        patched = true;
        exercise(); // FXSAVE fallback.
        unsigned a, b, c, d;
        if (__get_cpuid(1, &a, &b, &c, &d) && (c & bit_OSXSAVE)) {
            unsigned low, high;
            asm volatile("xgetbv" : "=a"(low), "=d"(high) : "c"(0));
            resident_xsave_mask = (uint64_t(high) << 32) | low;
            __cpuid_count(0xD, 0, a, b, c, d);
            require(b <= 65536, "Fixture CPU exceeds supported extended register state size");
            exercise();
        }
        require(entered == left && entered >= 100, "Before/after detours did not pair");
        write_code(patch.original);
        patched = false;
        resident_discard_hook(patch);
        uint64_t calls = 0;
        auto restored = resident_fixture(&calls, 2.0, 3.0);
        require(restored.x == 5.5 && restored.y == 2.5 && calls == 1, "Restored function changed behavior");
        integer_result = true;
        address = reinterpret_cast<uintptr_t>(resident_integer_fixture);
        resident_prepare_hook(patch, {address, reinterpret_cast<uintptr_t>(resident_integer_fixture_end)-address}, FR_CONTROL);
        write_code(patch.replacement);
        patched = true;
        for (unsigned i=0; i<100; ++i) {
            replace_result=i%2;
            require(resident_integer_fixture(0x1200, 0x34)==(replace_result ? 0x1201u : 0x1200u),
                "Scoped control override corrupted the integer return register");
        }
        write_code(patch.original);
        patched = false;
        resident_discard_hook(patch);
        require(resident_integer_fixture(0x1200, 0x34)==0x1234, "Integer fixture was not restored");
        bool rejected = false;
        try {
            resident_prepare_hook(patch, {reinterpret_cast<uintptr_t>(resident_fixture_branch),
                static_cast<size_t>(resident_fixture_branch_end - resident_fixture_branch)}, FR_CONTROL);
        } catch (const std::runtime_error &) { rejected = true; }
        require(rejected, "Unsupported control transfer was not rejected");
        resident_discard_hook(patch);
        return 0;
    } catch (const std::exception &error) {
        if (patched) write_code(patch.original);
        resident_discard_hook(patch);
        fprintf(stderr, "%s\n", error.what());
        return 1;
    }
}
