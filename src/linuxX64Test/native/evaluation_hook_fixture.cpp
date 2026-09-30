#include "evaluation_hook.h"
#include "gui_hook.h"
#include "pointer_hook.h"
#include <array>
#include <cassert>
#include <cerrno>
#include <cfenv>
#include <cstring>
#include <execinfo.h>
#include <stdexcept>
#include <thread>
#include <sys/mman.h>
#include <unistd.h>

extern "C" { uint64_t fm_evaluation_original = 0; }

namespace {
uint64_t cookie = 17;
unsigned before = 0, after = 0, aborted = 0;
void *expected = nullptr;

void vectorValue(unsigned char value) {
    alignas(32) std::array<unsigned char, 32> bytes;
    bytes.fill(value);
    if (fm_xsave_mode && (fm_xsave_low & 4))
        asm volatile("vmovups %0, %%ymm15" : : "m"(bytes) : "ymm15");
    else
        asm volatile("movdqu %0, %%xmm15" : : "m"(bytes) : "xmm15");
}

void checkVector(unsigned char expectedValue) {
    std::array<unsigned char, 32> bytes;
    const bool avx = fm_xsave_mode && (fm_xsave_low & 4);
    if (avx)
        asm volatile("vmovups %%ymm15, %0" : "=m"(bytes));
    else
        asm volatile("movdqu %%xmm15, %0" : "=m"(bytes));
    for (unsigned i = 0; i < (avx ? 32u : 16u); ++i)
        assert(bytes[i] == expectedValue);
}

struct Returned {
    uint64_t integer;
    double number;
};

struct Source {
    unsigned calls = 0;
    bool throws = false;
    virtual Returned evaluate() {
        checkVector(255);
        assert(std::fegetround() == FE_TONEAREST && expected == this);
        ++calls;
        if (throws)
            throw std::runtime_error("native evaluation failed");
        std::fesetround(FE_TOWARDZERO);
        errno = EDOM;
        vectorValue(170);
        return {0x12345678, 3.25};
    }
};

__attribute__((noinline)) Returned invoke(Source *source) {
    std::fesetround(FE_TONEAREST);
    vectorValue(255);
    asm volatile("" : "+r"(source) : : "memory");
    const auto result = source->evaluate();
    asm volatile("" : : : "memory");
    return result;
}

__attribute__((noinline)) void dirtyStack() {
    volatile unsigned char bytes[70000];
    for (size_t i = 0; i < sizeof(bytes); ++i)
        bytes[i] = 0xa5;
}

void checkReturn(Returned value) {
    checkVector(170);
    assert(value.integer == 0x12345678 && value.number == 3.25);
    assert(std::fegetround() == FE_TOWARDZERO && errno == EDOM);
}
} // namespace

extern "C" uint64_t fm_before_input_evaluation(void *receiver, uintptr_t caller) noexcept {
    const int saved = errno;
    assert(receiver == expected && caller);
    void *frames[16];
    assert(backtrace(frames, 16) >= 3);
    ++before;
    std::fesetround(FE_DOWNWARD);
    vectorValue(0);
    errno = saved;
    return cookie;
}

extern "C" void fm_after_input_evaluation(void *receiver, uint64_t observed) noexcept {
    const int saved = errno;
    assert(receiver == expected && observed == cookie && observed > 1);
    void *frames[16];
    assert(backtrace(frames, 16) >= 3);
    ++after;
    std::fesetround(FE_UPWARD);
    vectorValue(0);
    errno = saved;
}

extern "C" void fm_abort_input_evaluation(void *receiver, uint64_t observed) noexcept {
    const int saved = errno;
    assert(receiver == expected && observed == cookie && observed > 1);
    ++aborted;
    errno = saved;
}

static void checkMode() {
    before = after = aborted = 0;
    cookie = 17;
    Source source;
    expected = &source;
    uintptr_t *originalTable;
    std::memcpy(&originalTable, &source, sizeof(originalTable));
    const size_t size = sysconf(_SC_PAGESIZE);
    auto *table = static_cast<uintptr_t *>(mmap(nullptr, size, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    assert(table != MAP_FAILED);
    table[0] = originalTable[0];
    std::memcpy(&source, &table, sizeof(table));
    assert(mprotect(table, size, PROT_READ) == 0);
    fm_evaluation_original = originalTable[0];
    PointerHook hook(mprotect);
    assert(hook.install(reinterpret_cast<uintptr_t>(table), table[0],
        reinterpret_cast<uintptr_t>(&fm_evaluation_hook), size, PROT_READ) == 0);
    const auto late = reinterpret_cast<Returned (*)(Source *)>(table[0]);
    dirtyStack();
    checkReturn(invoke(&source));
    assert(before == 1 && after == 1 && source.calls == 1);
    cookie = 0;
    checkReturn(invoke(&source));
    assert(before == 2 && after == 1 && source.calls == 2);
    cookie = 1;
    source.throws = true;
    invoke(&source);
    assert(before == 3 && after == 1 && source.calls == 2);
    for (const uint64_t mode : {uint64_t{0}, uint64_t{17}}) {
        cookie = mode;
        try {
            invoke(&source);
            assert(false);
        } catch (const std::runtime_error &) {
            assert(after == 1);
            assert(aborted == (mode == 0 ? 0u : 1u));
        }
    }
    source.throws = false;
    cookie = 17;
    assert(hook.remove() == 0 && table[0] == fm_evaluation_original);
    std::fesetround(FE_TONEAREST);
    vectorValue(255);
    checkReturn(late(&source));
    assert(before == 6 && after == 2 && source.calls == 5);
    std::memcpy(&source, &originalTable, sizeof(originalTable));
    assert(munmap(table, size) == 0);
    std::fesetround(FE_TONEAREST);
}

int main() {
    assert(initializeHookState());
    const auto mode = fm_xsave_mode;
    checkMode();
    std::thread worker(checkMode);
    worker.join();
    if (mode) {
        fm_xsave_mode = 0;
        checkMode();
        std::thread fallbackWorker(checkMode);
        fallbackWorker.join();
    }
}
