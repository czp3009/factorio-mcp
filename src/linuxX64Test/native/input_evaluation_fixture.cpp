#include "input_evaluation.h"
#include "evaluation_hook.h"
#include "gui_hook.h"
#include <cassert>
#include <cerrno>
#include <functional>
#include <future>
#include <stdexcept>
#include <thread>
#include <sys/syscall.h>
#include <unistd.h>

extern "C" { uint64_t fm_evaluation_original = 0; }
extern "C" void fm_fixture_evaluation_invoke(uintptr_t receiver);
extern "C" const unsigned char fm_fixture_evaluation_return;

namespace {
const InputContext initial{0x1000, 0x2000, 0x3000, 0x4000, 0x5000, 10, 0, false};
InputContext current = initial;
int readError = 0;
unsigned reads = 0;
InputEvaluation *active = nullptr;
unsigned nativeCalls = 0;
bool nativeThrows = false;
bool reenterFrontend = false;
std::function<void()> onNative;

void nativeEvaluation(void *receiver) {
    assert(reinterpret_cast<uintptr_t>(receiver) == current.source);
    ++nativeCalls;
    if (onNative)
        onNative();
    if (reenterFrontend) {
        active->frontend();
        assert(active->pending());
    }
    if (nativeThrows)
        throw std::runtime_error("native evaluation threw through assembly");
}

void invokeHook() {
    fm_fixture_evaluation_invoke(current.source);
}

uintptr_t caller() { return reinterpret_cast<uintptr_t>(&fm_fixture_evaluation_return); }

__attribute__((noinline)) void deepFrontend(InputEvaluation &evaluation, unsigned depth) {
    if (depth)
        deepFrontend(evaluation, depth - 1);
    else
        evaluation.frontend();
    asm volatile("" : : : "memory");
}

int readContext(const FmLinuxInputContextConfig &, const uint32_t *cancel, InputContext &output) {
    ++reads;
    output = current;
    return *cancel ? ECANCELED : readError;
}

struct Emitter : InputEmitter {
    unsigned downs = 0, ups = 0;
    bool held = false, blockRelease = false;
    std::function<void()> onDown;
    void move(InputPosition) override { assert(false); }
    void wheel(int32_t) override { assert(false); }
    void button(InputButton, bool down) override {
        if (down) {
            assert(!held);
            held = true;
            ++downs;
            if (onDown)
                onDown();
        } else if (held) {
            if (blockRelease)
                throw std::runtime_error("release not yet entered");
            held = false;
            ++ups;
        }
    }
};

struct Fixture {
    ViewLifetime lifetime;
    InputTaskContext context{lifetime, readContext};
    InputSequence sequence{{{2, {{InputDevice::Keyboard, 42}}, {}, 0, {}}}};
    Emitter emitter;
    InputEvaluation evaluation;

    explicit Fixture(pid_t thread = getpid()) : evaluation(context, sequence, emitter, thread, caller()) {
        current = initial;
        readError = 0;
        uint32_t cancel = 0;
        InputContext output;
        assert(context.bind({}, &cancel, output) == 0);
    }

    uint64_t begin() { return evaluation.before(current.source, caller()); }
    void finish(uint64_t cookie) { evaluation.after(current.source, cookie); }
};
} // namespace

extern "C" uint64_t fm_before_input_evaluation(void *receiver, uintptr_t caller) noexcept {
    const int saved = errno;
    const auto result = active->before(reinterpret_cast<uintptr_t>(receiver), caller);
    errno = saved;
    return result;
}

extern "C" void fm_after_input_evaluation(void *receiver, uint64_t cookie) noexcept {
    const int saved = errno;
    active->after(reinterpret_cast<uintptr_t>(receiver), cookie);
    errno = saved;
}

extern "C" void fm_abort_input_evaluation(void *receiver, uint64_t cookie) noexcept {
    const int saved = errno;
    active->aborted(reinterpret_cast<uintptr_t>(receiver), cookie);
    errno = saved;
}

int main() {
    {
        Fixture f;
        std::thread requester([&] { f.evaluation.cancel(); });
        requester.join();
        const auto readCount = reads;
        assert(f.begin() == 0 && reads == readCount && !f.emitter.downs);
        f.evaluation.frontend();
        assert(f.evaluation.finished() && f.sequence.state() == InputSequenceState::Aborted);
        assert(!f.sequence.ticks() && !f.emitter.ups);
        f.context.release();
    }
    {
        Fixture f;
        const auto cookie = f.begin();
        assert(cookie > 1 && f.emitter.held);
        std::thread requester([&] { f.evaluation.cancel(); });
        requester.join();
        f.evaluation.frontend();
        assert(f.evaluation.pending() && !f.emitter.ups && !f.evaluation.finished());
        f.finish(cookie);
        assert(f.sequence.ticks() == 1 && f.sequence.state() == InputSequenceState::Releasing);
        f.emitter.blockRelease = true;
        f.evaluation.frontend();
        assert(!f.evaluation.finished() && f.emitter.held);
        f.emitter.blockRelease = false;
        f.evaluation.frontend();
        assert(f.evaluation.finished() && f.emitter.ups == 1 && !f.emitter.held);
        f.evaluation.cancel();
        f.evaluation.frontend();
        assert(f.emitter.ups == 1 && f.sequence.state() == InputSequenceState::Aborted);
        f.context.release();
    }
    {
        Fixture f;
        const auto readCount = reads;
        assert(f.evaluation.before(current.source, caller() + 1) == 0 && reads == readCount);
        assert(f.evaluation.before(current.source + 8, caller()) == 0 && f.emitter.downs == 0);
        f.emitter.onDown = [&] {
            assert(f.begin() == 0);
            f.evaluation.frontend();
            assert(f.sequence.state() == InputSequenceState::Running);
        };
        const auto first = f.begin();
        assert(first > 1 && f.sequence.ticks() == 0 && f.emitter.downs == 1);
        assert(f.begin() == 0); // Reentrant native call does not acquire another evaluation.
        f.finish(first);
        f.finish(first); // Duplicate completion cannot count twice.
        assert(f.sequence.ticks() == 1);
        f.finish(f.begin()); // Same tick, even with a fresh cookie, does not count twice.
        assert(f.sequence.ticks() == 1 && f.emitter.downs == 1);
        ++current.tick;
        f.finish(f.begin());
        assert(f.sequence.ticks() == 2 && f.sequence.state() == InputSequenceState::Running);
        ++current.tick;
        const auto last = f.begin();
        assert(f.sequence.state() == InputSequenceState::Succeeded && !f.evaluation.finished());
        f.finish(last);
        assert(f.sequence.state() == InputSequenceState::Succeeded && f.sequence.ticks() == 2);
        assert(f.evaluation.finished());
        assert(f.emitter.ups == 1 && !f.emitter.held && f.context.owned());
        f.context.release();
    }
    {
        Fixture f;
        const auto cookie = f.begin();
        f.emitter.blockRelease = true;
        // Native original threw: explicit unwind notification precedes eligible frontend cleanup.
        f.evaluation.aborted(current.source, cookie);
        f.evaluation.frontend();
        assert(f.sequence.state() == InputSequenceState::Releasing && f.context.owned());
        assert(f.sequence.ticks() == 0 && f.emitter.downs == 1 && f.emitter.ups == 0);
        f.finish(cookie);
        f.emitter.blockRelease = false;
        f.evaluation.frontend();
        assert(f.sequence.state() == InputSequenceState::Aborted && f.emitter.ups == 1);
        assert(f.sequence.ticks() == 0 && f.context.owned());
        f.context.release();
    }
    for (unsigned change = 0; change < 4; ++change) {
        Fixture f;
        f.emitter.onDown = [&] {
            if (change == 0) f.lifetime.retired(current.view);
            if (change == 1) current.source += 8;
            if (change == 2) current.paused = true;
            if (change == 3) ++current.tick;
        };
        assert(f.begin() == 1 && !f.evaluation.pending());
        f.evaluation.frontend();
        assert(f.sequence.ticks() == 0 && f.sequence.state() == InputSequenceState::Aborted);
        assert(f.emitter.downs == 1 && f.emitter.ups == 1);
        f.context.release();
    }
    for (unsigned change = 0; change < 4; ++change) {
        Fixture f;
        const auto cookie = f.begin();
        if (change == 0) f.lifetime.retired(current.view);
        if (change == 1) ++current.tick;
        if (change == 2) current.stopped = 1;
        if (change == 3) readError = EFAULT;
        f.finish(cookie);
        f.evaluation.frontend();
        assert(f.sequence.state() == InputSequenceState::Aborted && f.sequence.ticks() == 0);
        assert(f.emitter.ups == 1);
        f.context.release();
    }
    {
        Fixture f;
        const auto count = reads;
        std::thread other([&] {
            assert(f.begin() == 0);
            f.evaluation.frontend();
        });
        other.join();
        assert(reads == count);
        const auto cookie = f.begin();
        std::thread completion([&] { f.finish(cookie); });
        completion.join();
        assert(f.evaluation.pending() && f.sequence.ticks() == 0);
        f.finish(cookie + 1);
        f.finish(cookie);
        f.evaluation.frontend();
        assert(f.sequence.state() == InputSequenceState::Aborted && f.sequence.ticks() == 0);
        f.context.release();
    }
    assert(initializeHookState());
    {
        Fixture f;
        const auto cookie = f.begin();
        std::thread foreign([&] { f.evaluation.aborted(current.source, cookie); });
        foreign.join();
        assert(f.evaluation.pending() && f.sequence.state() == InputSequenceState::Running);
        f.evaluation.aborted(current.source + 8, cookie);
        assert(f.evaluation.pending() && f.emitter.ups == 0);
        f.evaluation.aborted(current.source, cookie);
        assert(!f.evaluation.pending() && f.sequence.ticks() == 0 && f.emitter.ups == 0);
        f.evaluation.frontend();
        assert(f.evaluation.finished() && f.emitter.ups == 1);
        f.context.release();
    }
    {
        Fixture f;
        const auto cookie = f.begin();
        deepFrontend(f.evaluation, 160);
        assert(f.evaluation.pending() && !f.evaluation.finished() && f.emitter.ups == 0);
        assert(f.sequence.state() == InputSequenceState::Running);
        f.evaluation.frontend();
        f.evaluation.aborted(current.source, cookie);
        f.finish(cookie);
        f.evaluation.frontend();
        assert(f.evaluation.finished() && f.emitter.ups == 1 && f.sequence.ticks() == 0);
        f.context.release();
    }
    fm_evaluation_original = reinterpret_cast<uintptr_t>(&nativeEvaluation);
    {
        Fixture f;
        active = &f.evaluation;
        reenterFrontend = true;
        invokeHook();
        ++current.tick;
        invokeHook();
        assert(f.sequence.ticks() == 2 && nativeCalls == 2 && f.emitter.downs == 1);
        ++current.tick;
        invokeHook();
        assert(f.sequence.state() == InputSequenceState::Succeeded && f.emitter.ups == 1);
        assert(nativeCalls == 3 && !f.evaluation.pending());
        reenterFrontend = false;
        f.context.release();
    }
    {
        Fixture f;
        active = &f.evaluation;
        nativeThrows = true;
        try {
            invokeHook();
            assert(false);
        } catch (const std::runtime_error &) {
            assert(!f.evaluation.pending() && f.sequence.ticks() == 0 && nativeCalls == 4);
            assert(f.emitter.ups == 0); // Unwind notification never dispatches cleanup into the game.
        }
        nativeThrows = false;
        f.evaluation.frontend();
        assert(f.sequence.state() == InputSequenceState::Aborted && f.emitter.ups == 1);
        assert(!f.evaluation.pending());
        f.context.release();
    }
    {
        Fixture f;
        active = &f.evaluation;
        f.emitter.onDown = [&] { f.lifetime.retired(current.view); };
        invokeHook();
        assert(nativeCalls == 4 && !f.evaluation.pending());
        assert(f.sequence.state() == InputSequenceState::Aborted && f.emitter.ups == 1);
        f.context.release();
    }
    for (const bool throws : {false, true}) {
        std::promise<pid_t> threadId;
        std::promise<void> start, entered, resume;
        auto startSignal = start.get_future();
        auto enteredSignal = entered.get_future();
        auto resumeSignal = resume.get_future();
        std::thread worker([&] {
            threadId.set_value(static_cast<pid_t>(syscall(SYS_gettid)));
            startSignal.get();
            try {
                invokeHook();
                assert(!throws);
            } catch (const std::runtime_error &) {
                assert(throws);
            }
        });
        Fixture f(threadId.get_future().get());
        active = &f.evaluation;
        nativeThrows = throws;
        onNative = [&] {
            // Keep the real assembly wrapper's original call active on the worker while the main thread
            // attempts cleanup. Futures establish fixture observation order without timing assumptions.
            entered.set_value();
            resumeSignal.get();
        };
        assert(f.begin() == 0); // Main thread cannot impersonate the established evaluating thread.
        start.set_value();
        enteredSignal.get();
        const auto readCount = reads;
        for (unsigned i = 0; i < 100; ++i) {
            f.evaluation.frontend();
            f.evaluation.after(current.source, 2);
            f.evaluation.aborted(current.source, 2);
            assert(f.evaluation.pending() && !f.evaluation.finished());
        }
        assert(reads == readCount && f.emitter.ups == 0 && f.sequence.ticks() == 0);
        resume.set_value();
        worker.join();
        assert(!f.evaluation.pending() && f.sequence.ticks() == (throws ? 0u : 1u));
        assert(f.emitter.ups == 0);
        current.paused = true;
        f.evaluation.frontend();
        assert(f.evaluation.finished() && f.emitter.ups == 1);
        f.context.release();
        onNative = {};
        nativeThrows = false;
    }
    {
        Fixture f;
        active = &f.evaluation;
        const auto count = nativeCalls;
        const auto readCount = reads;
        // A real call through the same wrapper from an unselected caller must forward transparently.
        reinterpret_cast<void (*)(uintptr_t)>(&fm_evaluation_hook)(current.source);
        assert(nativeCalls == count + 1 && reads == readCount);
        assert(!f.evaluation.pending() && f.emitter.downs == 0 && f.sequence.ticks() == 0);
        f.context.release();
    }
    active = nullptr;
}
