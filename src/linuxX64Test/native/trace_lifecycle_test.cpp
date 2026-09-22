// Exercise the actual tracer against threads born while its breakpoint is
// armed.
#include "../../linuxX64Main/native/bridge.cpp"
#include <atomic>
#include <sys/prctl.h>
#include <thread>

__attribute__((noinline)) void breakpoint_site() {
    asm volatile("" ::: "memory");
}
struct Shared {
    std::atomic<int> stage{0}, created{0};
};
int check_abort() {
    auto *stage = static_cast<std::atomic<int> *>(
        mmap(nullptr, sizeof(std::atomic<int>), PROT_READ | PROT_WRITE,
             MAP_SHARED | MAP_ANONYMOUS, -1, 0));
    if (stage == MAP_FAILED)
        return 1;
    new (stage) std::atomic<int>(0);
    int child = fork();
    if (child == 0) {
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        alarm(5);
        signal(SIGABRT, [](int) { _exit(91); });
        while (!stage->load())
            usleep(100);
        raise(SIGABRT);
        _exit(90);
    }
    if (child < 0)
        return 1;
    int result = 0;
    try {
        Trace tracer(child);
        stage->store(1);
        tracer.resume_others(-1);
        bool reported = false;
        try {
            tracer.wait_trap(child, 0);
        } catch (const std::exception &e) {
            reported = std::string(e.what()).find("remote call faulted") !=
                       std::string::npos;
        }
        if (!reported)
            fail("native abort was not reported at the signal stop");
        // A real remote-call failure retains the stopped target for inspection.
        // This isolated fixture suppresses its deliberate signal and exits
        // normally.
        tracer.threads.at(child).signal = 0;
        tracer.finish();
        int status = 0;
        waitpid(child, &status, 0);
        if (!WIFEXITED(status) || WEXITSTATUS(status) != 90)
            fail("abort reached the fixture's crash handler");
    } catch (const std::exception &e) {
        fprintf(stderr, "%s\n", e.what());
        result = 1;
    }
    kill(child, SIGKILL);
    waitpid(child, nullptr, 0);
    munmap(stage, sizeof(std::atomic<int>));
    return result;
}

int main(int argc, char **) {
    if (argc > 1)
        return check_abort();
    void *mapping = mmap(nullptr, sizeof(Shared), PROT_READ | PROT_WRITE,
                         MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (mapping == MAP_FAILED)
        return 1;
    auto *shared = new (mapping) Shared;
    int child = fork();
    if (child == 0) {
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        alarm(20);
        for (;;) {
            while (shared->stage != 1)
                usleep(100);
            std::thread worker([&] {
                while (shared->stage != 3)
                    usleep(100);
            });
            ++shared->created;
            shared->stage = 2;
            breakpoint_site();
            while (shared->stage != 3)
                usleep(100);
            worker.join();
            shared->stage = 0;
        }
    }
    if (child < 0)
        return 1;
    int result = 0;
    try {
        {
            Trace pending(child);
            if (pending.rendezvous(reinterpret_cast<U>(&breakpoint_site), 10,
                                   -1, 0, 0, true) != 0)
                fail("idle target unexpectedly reached the safe point");
            pending.finish();
            Trace observer(child);
            for (const auto &[tid, state] : observer.threads) {
                if (trace(PTRACE_PEEKUSER, tid, dr_offset(DR_CONTROL)) &
                    (DR_LOCAL_ENABLE_MASK | DR_GLOBAL_ENABLE_MASK))
                    fail("pending observation leaked a breakpoint");
            }
            observer.finish();
        }
        for (int i = 0; i < 1000; ++i) {
            while (shared->stage != 0)
                usleep(100);
            Trace tracer(child);
            shared->stage = 1;
            tracer.rendezvous(reinterpret_cast<U>(&breakpoint_site), 1000);
            tracer.finish();
            if (shared->created != i + 1)
                fail("child did not create the expected thread");
            Trace observer(child);
            for (const auto &[tid, state] : observer.threads) {
                U control = trace(PTRACE_PEEKUSER, tid, dr_offset(DR_CONTROL));
                if (control & (DR_LOCAL_ENABLE_MASK | DR_GLOBAL_ENABLE_MASK))
                    fail(fmt::format(
                        "iteration {}: breakpoint leaked onto tid {}", i, tid));
            }
            observer.finish();
            shared->stage = 3;
        }
    } catch (const std::exception &e) {
        fprintf(stderr, "%s\n", e.what());
        result = 1;
    }
    kill(child, SIGKILL);
    waitpid(child, nullptr, 0);
    munmap(mapping, sizeof(Shared));
    return result;
}
