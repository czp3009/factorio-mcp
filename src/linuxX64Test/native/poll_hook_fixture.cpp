#include "gui_hook.h"
#include "poll_hook.h"
#include "pointer_hook.h"
#include "../../nativeMain/native/key_gesture.h"
#include <array>
#include <cassert>
#include <cfenv>
#include <cstring>
#include <execinfo.h>
#include <stdexcept>
#include <sys/mman.h>
#include <unistd.h>

extern "C" {
uint64_t fm_poll_original = 0;
uintptr_t fm_fixture_poll_stack = 0;
}

struct Event {
    uint32_t key;
    bool down;
    unsigned sentinel;
};

class Window {
public:
    bool real = false, throws = false;
    unsigned calls = 0;

    virtual bool poll(Event &event) {
        std::array<unsigned char, 16> entryVector;
        asm volatile("movdqu %%xmm14, %0" : "=m"(entryVector));
        for (const auto byte : entryVector)
            assert(byte == 255);
        assert(std::fegetround() == FE_TONEAREST);
        ++calls;
        if (throws)
            throw std::runtime_error("poll fixture exception");
        if (real)
            event = {1234, true, 5678};
        asm volatile("pcmpeqb %%xmm15, %%xmm15" ::: "xmm15");
        if (fm_xsave_mode && (fm_xsave_low & 4)) {
            alignas(32) const uint64_t vector[] = {~uint64_t{0}, ~uint64_t{0}, ~uint64_t{0}, ~uint64_t{0}};
            asm volatile("vmovups %0, %%ymm15" : : "m"(vector) : "ymm15");
        }
        return real;
    }
};

static KeyGesture gesture;
static Window *expectedWindow;
static unsigned callbacks;
static bool admit;
static int intercepted = -1;
static unsigned beforeCallbacks;

extern "C" int fm_before_poll(void *receiver, void *event, uintptr_t caller, uintptr_t callerStack) noexcept {
    ++beforeCallbacks;
    assert(receiver == expectedWindow && caller != 0 && callerStack == fm_fixture_poll_stack);
    void *frames[16];
    assert(backtrace(frames, 16) >= 5);
    std::fesetround(FE_UPWARD);
    asm volatile("pxor %%xmm14, %%xmm14" ::: "xmm14");
    if (intercepted == 1)
        *static_cast<Event *>(event) = {4321, false, 8765};
    return intercepted;
}

extern "C" bool fm_after_empty_poll(void *receiver, void *event, uintptr_t caller, uintptr_t callerStack) noexcept {
    ++callbacks;
    assert(receiver == expectedWindow && caller != 0 && callerStack == fm_fixture_poll_stack);
    void *frames[16];
    assert(backtrace(frames, 16) >= 5);
    auto &output = *static_cast<Event *>(event);
    assert(output.sentinel == 42);
    std::fesetround(FE_DOWNWARD);
    asm volatile("pxor %%xmm15, %%xmm15" ::: "xmm15");
    if (fm_xsave_mode && (fm_xsave_low & 4))
        asm volatile("vxorps %%ymm15, %%ymm15, %%ymm15" ::: "ymm15");
    if (!admit)
        return false;
    return gesture.next(output.key, output.down);
}

__attribute__((noinline)) static bool invokeFramed(Window *window, Event &event) {
    asm volatile("movq %%rsp, %0" : "=m"(fm_fixture_poll_stack));
    asm volatile("pcmpeqb %%xmm14, %%xmm14" ::: "xmm14");
    const bool result = window->poll(event);
    // Prevent devirtualization and retain this actual caller's frame in optimized builds.
    asm volatile("" : : "r"(window) : "memory");
    return result;
}

extern "C" bool fm_fixture_poll_frameless(Window *, Event &);

int main() {
    alarm(30);
    assert(std::fesetround(FE_TONEAREST) == 0);
    assert(initializeHookState());
    const auto availableMode = fm_xsave_mode;
    const size_t pageSize = sysconf(_SC_PAGESIZE);
    void *page = mmap(nullptr, pageSize, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(page != MAP_FAILED);
    Window window;
    expectedWindow = &window;
    uintptr_t *originalTable;
    std::memcpy(&originalTable, static_cast<void *>(&window), sizeof(originalTable));
    auto *table = static_cast<uintptr_t *>(page);
    std::memcpy(table, originalTable - 2, 3 * sizeof(uintptr_t));
    auto *addressPoint = table + 2;
    std::memcpy(static_cast<void *>(&window), &addressPoint, sizeof(addressPoint));
    fm_poll_original = table[2];
    assert(mprotect(page, pageSize, PROT_READ) == 0);
    PointerHook hook(mprotect);
    for (auto invoke : {invokeFramed, fm_fixture_poll_frameless}) {
        for (uint32_t mode = 0; mode <= availableMode; ++mode) {
            fm_xsave_mode = mode;
            assert(hook.install(reinterpret_cast<uintptr_t>(addressPoint), fm_poll_original,
                reinterpret_cast<uintptr_t>(&fm_poll_hook), pageSize, PROT_READ) == 0);
            const uint32_t keys[] = {13, 19};
            gesture.begin(keys, 2);
            admit = true;
            for (unsigned edge = 0; edge < 5; ++edge) {
                // A real event ahead of every synthetic edge cannot consume or acknowledge it.
                window.real = true;
                Event event{0, false, 42};
                const auto before = callbacks;
                assert(invoke(&window, event));
                assert(event.key == 1234 && event.down && event.sentinel == 5678 && callbacks == before);
                assert(gesture.active());
                window.real = false;
                event = {0, false, 42};
                std::array<unsigned char, 32> vector;
                const bool avx = fm_xsave_mode && (fm_xsave_low & 4);
                const int rounding = std::fegetround();
                const bool produced = invoke(&window, event);
                if (avx)
                    asm volatile("vmovups %%ymm15, %0" : "=m"(vector));
                else
                    asm volatile("movdqu %%xmm15, %0" : "=m"(vector));
                assert(std::fegetround() == rounding);
                for (size_t index = 0; index < (avx ? 32u : 16u); ++index)
                    assert(vector[index] == 255);
                assert(callbacks == before + 1 && event.sentinel == 42 && produced == (edge < 4));
                if (edge < 4)
                    assert(event.down == (edge < 2) && event.key == keys[edge < 2 ? edge : 3 - edge]);
            }
            assert(!gesture.active());
            gesture.begin(keys, 2);
            admit = false;
            Event event{77, true, 42};
            assert(!invoke(&window, event) && event.key == 77 && event.down && gesture.active());
            gesture.cancel();
            admit = true;
            assert(!invoke(&window, event) && !gesture.active());
            window.throws = true;
            const auto before = callbacks;
            try {
                invoke(&window, event);
                assert(false);
            } catch (const std::runtime_error &) {
                assert(callbacks == before);
            }
            window.throws = false;
            const auto originalCalls = window.calls;
            const auto originalBeforeCallbacks = beforeCallbacks;
            window.throws = true;
            for (int replacement = 0; replacement <= 1; ++replacement) {
                intercepted = replacement;
                event = {77, true, 42};
                assert(invoke(&window, event) == (replacement != 0));
                assert(window.calls == originalCalls && callbacks == before);
                assert(beforeCallbacks == originalBeforeCallbacks + replacement + 1);
                assert(std::fegetround() == FE_TONEAREST);
                assert(event.key == (replacement ? 4321u : 77u) && event.sentinel == (replacement ? 8765u : 42u));
            }
            intercepted = -1;
            window.throws = false;
            assert(hook.remove() == 0 && !hook.hasOwnership());
            assert(!invoke(&window, event) && callbacks == before);
            assert(hook.remove() == 0);
        }
    }
    std::memcpy(static_cast<void *>(&window), &originalTable, sizeof(originalTable));
    assert(munmap(page, pageSize) == 0);
}
