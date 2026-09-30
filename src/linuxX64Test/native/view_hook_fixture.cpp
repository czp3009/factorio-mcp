#include "gui_hook.h"
#include "pointer_hook.h"
#include "view_lifetime.h"
#include <array>
#include <cassert>
#include <cerrno>
#include <cfenv>
#include <cstring>
#include <execinfo.h>
#include <stdexcept>
#include <sys/mman.h>
#include <thread>
#include <unistd.h>

extern "C" { uint64_t fm_view_retire_original = 0; }

namespace {
ViewLifetime lifetime;
unsigned notifications = 0;
bool unwound = false;
void *observed = nullptr;

struct Returned {
    uint64_t integer;
    double floating;
};

struct FixtureView {
    unsigned calls = 0;
    bool throws = false;
    virtual Returned retire(uint64_t a, uint64_t b, uint64_t c, uint64_t d, uint64_t e, uint64_t f, double number);
};

Returned FixtureView::retire(uint64_t a, uint64_t b, uint64_t c, uint64_t d, uint64_t e, uint64_t f, double number) {
    std::array<unsigned char, 32> vector;
    if (fm_xsave_mode && (fm_xsave_low & 4))
        asm volatile("vmovups %%ymm15, %0" : "=m"(vector));
    else
        asm volatile("movdqu %%xmm15, %0" : "=m"(vector));
    for (size_t index = 0; index < (fm_xsave_mode && (fm_xsave_low & 4) ? 32u : 16u); ++index)
        assert(vector[index] == 255);
    assert(a == 11 && b == 22 && c == 33 && d == 44 && e == 55 && f == 66 && number == 3.25);
    assert(std::fegetround() == FE_TONEAREST);
    assert(observed == this && unwound && notifications > calls);
    ++calls;
    if (throws) throw std::runtime_error("native retirement exception");
    return {a + b + c + d + e + f, number};
}

__attribute__((noinline)) Returned invoke(FixtureView *view) {
    if (fm_xsave_mode && (fm_xsave_low & 4)) {
        alignas(32) const uint64_t vector[4] = {~uint64_t{0}, ~uint64_t{0}, ~uint64_t{0}, ~uint64_t{0}};
        asm volatile("vmovups %0, %%ymm15" : : "m"(vector) : "ymm15");
    } else
        asm volatile("pcmpeqd %%xmm15, %%xmm15" ::: "xmm15");
    return view->retire(11, 22, 33, 44, 55, 66, 3.25);
}

__attribute__((noinline)) void dirtyStack() {
    // The XSAVE header contains reserved bytes which XSAVE does not write and XRSTOR requires to be zero.
    // Do not let freshly zeroed OS stack pages hide incomplete initialization in the assembly wrapper.
    volatile unsigned char bytes[70000];
    for (size_t index = 0; index < sizeof(bytes); ++index)
        bytes[index] = 0xa5;
}

void testGuard() {
    constexpr uintptr_t view = 0x1000, other = 0x2000;
    assert(!lifetime.owned() && !lifetime.valid(view));
    assert(lifetime.bind(0, lifetime.generation()) == EINVAL);
    auto generation = lifetime.generation();
    lifetime.retired(other);
    assert(lifetime.bind(view, generation) == ESTALE && !lifetime.owned());
    assert(lifetime.bind(view, lifetime.generation()) == 0 && lifetime.valid(view));
    assert(lifetime.bind(view, lifetime.generation()) == EBUSY);
    lifetime.retired(other);
    assert(lifetime.valid(view) && !lifetime.valid(other));
    lifetime.retired(view);
    // Address reuse never revives the old task. Only explicit release followed by new admission may bind again.
    assert(lifetime.owned() && !lifetime.valid(view));
    assert(lifetime.bind(view, lifetime.generation()) == EBUSY);
    lifetime.release();
    assert(!lifetime.owned() && lifetime.bind(view, lifetime.generation()) == 0 && lifetime.valid(view));
    lifetime.release();
    for (unsigned iteration = 0; iteration < 64; ++iteration) {
        std::atomic<bool> start{false};
        generation = lifetime.generation();
        std::thread worker([&] {
            while (!start.load()) std::this_thread::yield();
            lifetime.retired(view);
        });
        start.store(true);
        const int result = lifetime.bind(view, generation);
        worker.join();
        assert((result == 0 || result == ESTALE) && !lifetime.valid(view));
        lifetime.release();
    }
}
} // namespace

extern "C" void fm_before_view_retire(void *receiver) noexcept {
    observed = receiver;
    ++notifications;
    lifetime.retired(reinterpret_cast<uintptr_t>(receiver));
    void *frames[16];
    unwound = backtrace(frames, 16) >= 5;
    std::fesetround(FE_DOWNWARD);
    asm volatile("pxor %%xmm0, %%xmm0\npxor %%xmm15, %%xmm15" ::: "xmm0", "xmm15");
    if (fm_xsave_mode && (fm_xsave_low & 4))
        asm volatile("vxorps %%ymm15, %%ymm15, %%ymm15" ::: "ymm15");
}

int main() {
    alarm(30);
    testGuard();
    assert(initializeHookState());
    const auto available = fm_xsave_mode;
    const size_t pageSize = sysconf(_SC_PAGESIZE);
    auto *page = static_cast<uintptr_t *>(mmap(nullptr, pageSize, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    assert(page != MAP_FAILED);
    FixtureView view;
    uintptr_t *original;
    std::memcpy(&original, &view, sizeof(original));
    std::memcpy(page, original - 2, 3 * sizeof(uintptr_t));
    auto *table = page + 2;
    std::memcpy(&view, &table, sizeof(table));
    fm_view_retire_original = table[0];
    assert(mprotect(page, pageSize, PROT_READ) == 0);
    PointerHook hook(mprotect);
    for (uint32_t mode = 0; mode <= available; ++mode) {
        fm_xsave_mode = mode;
        assert(hook.install(reinterpret_cast<uintptr_t>(table), fm_view_retire_original,
            reinterpret_cast<uintptr_t>(&fm_view_retire_hook), pageSize, PROT_READ) == 0);
        const auto identity = reinterpret_cast<uintptr_t>(&view);
        assert(lifetime.bind(identity, lifetime.generation()) == 0);
        const auto before = notifications;
        dirtyStack();
        const auto returned = invoke(&view);
        assert(returned.integer == 231 && returned.floating == 3.25 && notifications == before + 1);
        assert(!lifetime.valid(identity));
        view.throws = true;
        try {
            invoke(&view);
            assert(false);
        } catch (const std::runtime_error &) {
            assert(notifications == before + 2);
        }
        view.throws = false;
        lifetime.release();
        assert(hook.remove() == 0 && !hook.hasOwnership());
        assert(hook.remove() == 0 && table[0] == fm_view_retire_original);
    }
    std::memcpy(&view, &original, sizeof(original));
    assert(munmap(page, pageSize) == 0);
}
