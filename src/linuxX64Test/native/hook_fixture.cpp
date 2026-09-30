#include "gui_hook.h"
#include "pointer_hook.h"
#include <array>
#include <cerrno>
#include <cfenv>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <execinfo.h>
#include <stdexcept>
#include <sys/mman.h>
#include <unistd.h>

static void require(bool condition, const char *message) {
    if (!condition) {
        std::fprintf(stderr, "factorio-mcp hook fixture: %s\n", message);
        std::abort();
    }
}

struct Returned {
    uint64_t integer;
    double floating;
};

class FixtureGui {
public:
    unsigned calls = 0;
    bool throws = false;

    virtual Returned logic(bool flag);
};

Returned FixtureGui::logic(bool flag) {
    ++calls;
    if (throws)
        throw std::runtime_error("fixture exception");
    asm volatile("pcmpeqb %%xmm15, %%xmm15" ::: "xmm15");
    if (fm_xsave_mode && (fm_xsave_low & 4)) {
        alignas(32) const uint64_t vector[4] = {~uint64_t{0}, ~uint64_t{0}, ~uint64_t{0}, ~uint64_t{0}};
        asm volatile("vmovups %0, %%ymm15" : : "m"(vector) : "ymm15");
    }
    return {0x1234567800000000ULL + calls, flag ? 3.25 : -7.5};
}

static unsigned callbacks;
static void *observedReceiver;
static uintptr_t observedCaller;
static bool backtraceComplete;

extern "C" void fm_after_gui_logic(void *receiver, uintptr_t caller) noexcept {
    ++callbacks;
    observedReceiver = receiver;
    observedCaller = caller;
    void *frames[16];
    const int count = backtrace(frames, 16);
    backtraceComplete = count >= 5;
    std::fesetround(FE_DOWNWARD);
    asm volatile("pxor %%xmm0, %%xmm0\npxor %%xmm15, %%xmm15" ::: "xmm0", "xmm15");
    if (fm_xsave_mode && (fm_xsave_low & 4))
        asm volatile("vxorps %%ymm15, %%ymm15, %%ymm15" ::: "ymm15");
}

__attribute__((noinline)) static Returned invoke(FixtureGui *gui, bool flag) {
    return gui->logic(flag);
}

static unsigned protectionCalls;
static unsigned failProtectionCall;

static int protect(void *address, size_t size, int flags) {
    if (++protectionCalls == failProtectionCall) {
        errno = EACCES;
        return -1;
    }
    return mprotect(address, size, flags);
}

int main() {
    alarm(30);
    require(initializeHookState(), "CPU extended state is unsupported");
    const uint32_t availableMode = fm_xsave_mode;
    const size_t pageSize = sysconf(_SC_PAGESIZE);
    void *page = mmap(nullptr, pageSize, PROT_READ | PROT_WRITE, MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
    require(page != MAP_FAILED, "cannot allocate fixture table");
    FixtureGui gui;
    uintptr_t *originalTable;
    std::memcpy(&originalTable, static_cast<void *>(&gui), sizeof(originalTable));
    auto *table = static_cast<uintptr_t *>(page);
    // These are synthetic Itanium table headers, not game member positions.
    std::memcpy(table, originalTable - 2, 3 * sizeof(uintptr_t));
    uintptr_t *addressPoint = table + 2;
    std::memcpy(static_cast<void *>(&gui), &addressPoint, sizeof(addressPoint));
    fm_gui_original = table[2];
    const uintptr_t entry = reinterpret_cast<uintptr_t>(addressPoint);
    const uintptr_t replacement = reinterpret_cast<uintptr_t>(&fm_gui_hook);
    require(mprotect(page, pageSize, PROT_READ) == 0, "cannot protect fixture table");
    PointerHook hook(protect);

    for (uint32_t mode = 0; mode <= availableMode; ++mode) {
        fm_xsave_mode = mode;
        require(hook.install(entry, fm_gui_original, replacement, pageSize, PROT_READ) == 0, "install failed");
        const int rounding = std::fegetround();
        const unsigned before = callbacks;
        for (bool flag : {false, true}) {
            std::array<unsigned char, 32> vector;
            const bool avx = fm_xsave_mode && (fm_xsave_low & 4);
            const auto result = invoke(&gui, flag);
            if (avx)
                asm volatile("vmovups %%ymm15, %0" : "=m"(vector));
            else
                asm volatile("movdqu %%xmm15, %0" : "=m"(vector));
            require(result.integer == 0x1234567800000000ULL + gui.calls, "integer return changed");
            require(result.floating == (flag ? 3.25 : -7.5), "floating return changed");
            for (size_t index = 0; index < (avx ? 32u : 16u); ++index)
                require(vector[index] == 255, "SIMD return state changed");
            require(std::fegetround() == rounding, "floating environment changed");
            require(observedReceiver == &gui && observedCaller != 0, "callback arguments changed");
            require(backtraceComplete, "callback stack could not unwind across assembly");
        }
        require(callbacks == before + 2, "original/callback dispatch count changed");
        gui.throws = true;
        try {
            invoke(&gui, true);
            require(false, "original exception was swallowed");
        } catch (const std::runtime_error &) {
            require(callbacks == before + 2, "callback ran after exceptional logic");
        }
        gui.throws = false;
        require(hook.remove() == 0 && !hook.hasOwnership(), "remove failed");
        invoke(&gui, true);
        require(callbacks == before + 2, "callback survived removal");
        require(hook.remove() == 0, "repeat removal failed");
    }

    failProtectionCall = protectionCalls + 1;
    require(hook.install(entry, fm_gui_original, replacement, pageSize, PROT_READ) == EACCES,
            "initial protection failure was ignored");
    require(!hook.hasOwnership() && table[2] == fm_gui_original, "failed install changed pointer");
    failProtectionCall = protectionCalls + 2;
    require(hook.install(entry, fm_gui_original, replacement, pageSize, PROT_READ) == EACCES,
            "restore protection failure was ignored");
    require(hook.ownsPointer() && hook.ownsProtection(), "partial install ownership was lost");
    failProtectionCall = 0;
    require(hook.remove() == 0 && !hook.hasOwnership(), "partial install cleanup failed");

    require(hook.install(entry, fm_gui_original, replacement, pageSize, PROT_READ) == 0, "reinstall failed");
    require(mprotect(page, pageSize, PROT_READ | PROT_WRITE) == 0, "cannot inject ownership loss");
    table[2] = fm_gui_original;
    require(mprotect(page, pageSize, PROT_READ) == 0, "cannot restore injected protection");
    const auto beforeConflict = protectionCalls;
    require(hook.remove() == ESTALE && hook.hasOwnership(), "foreign pointer was overwritten");
    require(protectionCalls == beforeConflict, "ownership mismatch changed page protection");
    require(mprotect(page, pageSize, PROT_READ | PROT_WRITE) == 0, "cannot repair fixture ownership");
    table[2] = replacement;
    require(mprotect(page, pageSize, PROT_READ) == 0, "cannot finish fixture ownership repair");
    failProtectionCall = protectionCalls + 2;
    require(hook.remove() == EACCES, "removal protection failure was ignored");
    require(!hook.ownsPointer() && hook.ownsProtection() && table[2] == fm_gui_original,
            "partial removal state is incorrect");
    failProtectionCall = 0;
    require(hook.remove() == 0 && !hook.hasOwnership(), "partial removal retry failed");
    std::memcpy(static_cast<void *>(&gui), &originalTable, sizeof(originalTable));
    require(munmap(page, pageSize) == 0, "cannot release fixture table");
    std::puts("factorio-mcp hook fixture: passed");
}
