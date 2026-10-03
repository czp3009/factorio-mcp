#include "worker_completion.h"
#include "gui_hook.h"
#include "pointer_hook.h"
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

extern "C" { uint64_t fm_worker_completion_original = 0; }

namespace {
struct FixtureWorker {
    uintptr_t padding[FIXTURE_PADDING];
    void *listener;
};

unsigned notifications = 0;
void *observedListener = nullptr;
void *observedWorker = nullptr;
uintptr_t observedCaller = 0;
bool unwound = false;

struct Returned {
    uint64_t integer;
    double floating;
};

struct FixtureListener {
    bool throws = false;
    virtual Returned complete(FixtureWorker *worker, uint64_t a, uint64_t b, uint64_t c, uint64_t d,
                              uint64_t e, double floating);
};

Returned FixtureListener::complete(FixtureWorker *worker, uint64_t a, uint64_t b, uint64_t c, uint64_t d,
                                   uint64_t e, double floating) {
    std::array<unsigned char, 32> vector;
    if (fm_xsave_mode && (fm_xsave_low & 4))
        asm volatile("vmovups %%ymm15, %0" : "=m"(vector));
    else
        asm volatile("movdqu %%xmm15, %0" : "=m"(vector));
    for (size_t index = 0; index < (fm_xsave_mode && (fm_xsave_low & 4) ? 32u : 16u); ++index)
        assert(vector[index] == 255);
    assert(observedListener == this && observedWorker == worker && worker->listener == this);
    assert(a == 11 && b == 22 && c == 33 && d == 44 && e == 55 && floating == 3.25);
    assert(observedCaller && unwound && std::fegetround() == FE_TONEAREST && errno == EDOM);
    if (throws)
        throw std::runtime_error("native worker completion exception");
    return {a + b + c + d + e, floating};
}

__attribute__((noinline)) Returned invoke(FixtureListener *listener, FixtureWorker *worker) {
    if (fm_xsave_mode && (fm_xsave_low & 4)) {
        alignas(32) const uint64_t vector[4] = {~uint64_t{0}, ~uint64_t{0}, ~uint64_t{0}, ~uint64_t{0}};
        asm volatile("vmovups %0, %%ymm15" : : "m"(vector) : "ymm15");
    } else
        asm volatile("pcmpeqd %%xmm15, %%xmm15" ::: "xmm15");
    return listener->complete(worker, 11, 22, 33, 44, 55, 3.25);
}

__attribute__((noinline)) void dirtyStack() {
    volatile unsigned char bytes[70000];
    for (size_t index = 0; index < sizeof(bytes); ++index)
        bytes[index] = 0xa5;
}

void testBinding() {
    FixtureListener listener, otherListener;
    FixtureWorker worker{{}, &listener}, otherWorker{{}, &listener};
    uintptr_t table;
    std::memcpy(&table, static_cast<const void *>(&listener), sizeof(table));
    uintptr_t type;
    std::memcpy(&type, reinterpret_cast<void *>(table - 8), sizeof(type));
    WorkerCompletionLayout layout{table, type, reinterpret_cast<uintptr_t>(&testBinding), sizeof(worker),
                                  offsetof(FixtureWorker, listener)};
    const auto first = reinterpret_cast<uintptr_t>(&listener);
    const auto target = reinterpret_cast<uintptr_t>(&worker);
    WorkerCompletion binding(layout);
    WorkerIdentity identity;
    assert(binding.identity(identity) == ENOENT && !identity.thread && !identity.worker && !identity.listener);
    assert(binding.observe(first, target, layout.caller) == EPERM);
    assert(binding.identity(identity) == ENOENT);
    std::thread owner([&] {
        assert(binding.observe(first, target, layout.caller + 1) == EPERM);
        assert(binding.observe(first, 8, layout.caller) == EFAULT);
        assert(binding.observe(reinterpret_cast<uintptr_t>(&otherListener), target, layout.caller) == ESTALE);
        assert(binding.identity(identity) == ENOENT);
        assert(binding.observe(first, target, layout.caller) == 0);
        assert(binding.observe(first, target, layout.caller) == 0);
        assert(binding.identity(identity) == 0 && identity.thread != getpid() &&
            identity.worker == target && identity.listener == first);
        assert(binding.observe(first, reinterpret_cast<uintptr_t>(&otherWorker), layout.caller) == ESTALE);
        assert(binding.identity(identity) == ESTALE && !identity.thread);
        assert(binding.observe(first, target, layout.caller) == ESTALE);
    });
    owner.join();
    layout.listenerMember = sizeof(worker);
    WorkerCompletion invalid(layout);
    assert(invalid.identity(identity) == EINVAL && !identity.thread);

    layout.listenerMember = offsetof(FixtureWorker, listener);
    WorkerCompletion changedThread(layout);
    std::atomic<bool> admitted{false}, changed{false};
    std::thread original([&] {
        assert(changedThread.observe(first, target, layout.caller) == 0);
        admitted.store(true);
        while (!changed.load())
            std::this_thread::yield();
    });
    while (!admitted.load())
        std::this_thread::yield();
    std::thread replacement([&] {
        assert(changedThread.observe(first, target, layout.caller) == ESTALE);
        changed.store(true);
    });
    replacement.join();
    original.join();
    assert(changedThread.identity(identity) == ESTALE && !identity.thread);
}
} // namespace

extern "C" void fm_before_worker_completion(void *listener, void *worker, uintptr_t caller) noexcept {
    const auto savedErrno = errno;
    observedListener = listener;
    observedWorker = worker;
    observedCaller = caller;
    ++notifications;
    void *frames[16];
    unwound = backtrace(frames, 16) >= 5;
    std::fesetround(FE_DOWNWARD);
    asm volatile("pxor %%xmm0, %%xmm0\npxor %%xmm15, %%xmm15" ::: "xmm0", "xmm15");
    if (fm_xsave_mode && (fm_xsave_low & 4))
        asm volatile("vxorps %%ymm15, %%ymm15, %%ymm15" ::: "ymm15");
    errno = savedErrno;
}

int main() {
    alarm(30);
    testBinding();
    assert(initializeHookState());
    const auto available = fm_xsave_mode;
    const size_t pageSize = sysconf(_SC_PAGESIZE);
    auto *page = static_cast<uintptr_t *>(mmap(nullptr, pageSize, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    assert(page != MAP_FAILED);
    FixtureListener listener;
    FixtureWorker worker{{}, &listener};
    uintptr_t *original;
    std::memcpy(&original, static_cast<const void *>(&listener), sizeof(original));
    std::memcpy(page, original - 2, 3 * sizeof(uintptr_t));
    auto *table = page + 2;
    std::memcpy(static_cast<void *>(&listener), &table, sizeof(table));
    fm_worker_completion_original = table[0];
    assert(mprotect(page, pageSize, PROT_READ) == 0);
    PointerHook hook(mprotect);
    for (uint32_t mode = 0; mode <= available; ++mode) {
        fm_xsave_mode = mode;
        assert(hook.install(reinterpret_cast<uintptr_t>(table), fm_worker_completion_original,
            reinterpret_cast<uintptr_t>(&fm_worker_completion_hook), pageSize, PROT_READ) == 0);
        const auto before = notifications;
        dirtyStack();
        errno = EDOM;
        const auto returned = invoke(&listener, &worker);
        assert(returned.integer == 165 && returned.floating == 3.25 && notifications == before + 1);
        listener.throws = true;
        try {
            invoke(&listener, &worker);
            assert(false);
        } catch (const std::runtime_error &) {
            assert(notifications == before + 2);
        }
        listener.throws = false;
        assert(hook.remove() == 0 && !hook.hasOwnership());
        assert(hook.remove() == 0 && table[0] == fm_worker_completion_original);
    }
    std::memcpy(static_cast<void *>(&listener), &original, sizeof(original));
    assert(munmap(page, pageSize) == 0);
}
