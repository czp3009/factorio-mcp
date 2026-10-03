#include "input_task_session.h"
#include <cassert>
#include <cerrno>
#include <fcntl.h>
#include <linux/memfd.h>
#include <stdexcept>
#include <thread>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static bool failClose = false;
extern "C" int __real_close(int);

extern "C" int __wrap_close(int descriptor) {
    const int result = __real_close(descriptor);
    if (failClose && result == 0) {
        failClose = false;
        errno = EIO;
        return -1;
    }
    return result;
}

namespace {
InputContext current{0x1000, 0x2000, 0x3000, 0x4000, 0x5000, 20, 0, false};
constexpr uintptr_t caller = 0x12340;

int readContext(const FmLinuxInputContextConfig &, const uint32_t *cancel, InputContext &output) {
    output = current;
    return fm_ipc_load(cancel) ? ECANCELED : 0;
}

struct Wire {
    int descriptor;
    FmLinuxInputTask *task;

    Wire() {
        descriptor = syscall(SYS_memfd_create, "factorio-mcp-input-session", MFD_CLOEXEC | MFD_ALLOW_SEALING);
        assert(descriptor >= 0 && ftruncate(descriptor, sizeof(FmLinuxInputTask)) == 0);
        assert(fcntl(descriptor, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW) == 0);
        void *view = mmap(nullptr, sizeof(FmLinuxInputTask), PROT_READ | PROT_WRITE, MAP_SHARED, descriptor, 0);
        assert(view != MAP_FAILED);
        task = static_cast<FmLinuxInputTask *>(view);
        task->ownerPid = task->targetPid = getpid();
        task->count = 1;
        task->operations[0].ticks = 2;
        task->operations[0].count = 1;
        task->operations[0].buttons[0] = {static_cast<uint32_t>(InputDevice::Keyboard), 42};
    }

    ~Wire() {
        assert(munmap(task, sizeof(FmLinuxInputTask)) == 0);
        assert(close(descriptor) == 0);
    }
};

struct Emitter : InputEmitter {
    unsigned down = 0, up = 0;
    bool held = false, preventUp = false;

    void move(InputPosition) override {
        assert(false);
    }

    void wheel(int32_t) override {
        assert(false);
    }

    void button(InputButton value, bool pressed) override {
        assert(value == (InputButton{InputDevice::Keyboard, 42}));
        if (pressed) {
            assert(!held);
            held = true;
            ++down;
        } else {
            if (preventUp)
                throw std::runtime_error("Release has not entered");
            assert(held);
            held = false;
            ++up;
        }
    }
};

struct Fixture {
    ViewLifetime lifetime;
    Emitter emitter;
    InputTaskSession session{lifetime, readContext};
    Wire wire;

    void start() {
        assert(session.start({}, getpid(), wire.descriptor, getpid(), caller, emitter) == 0);
        assert(session.owned() && lifetime.owned() && fm_linux_input_state(wire.task) == 1);
    }

    void tick() {
        const auto cookie = session.before(current.source, caller);
        assert(cookie > 1);
        assert(session.frontend() == EBUSY);
        session.after(current.source, cookie);
        session.after(current.source, cookie);
    }
};
} // namespace

int main() {
    {
        Fixture f;
        f.start();
        Wire second;
        assert(f.session.start({}, getpid(), second.descriptor, getpid(), caller, f.emitter) == EBUSY);
        assert(fm_linux_input_state(second.task) == 0);
        assert(f.session.before(current.source, caller + 1) == 0);
        std::thread unrelated([&] { assert(f.session.before(current.source, caller) == 0); });
        unrelated.join();
        f.tick();
        f.tick(); // Repeated native tick cannot advance the sequence twice.
        assert(fm_linux_input_ticks(f.wire.task) == 1 && f.emitter.down == 1);
        ++current.tick;
        f.tick();
        ++current.tick;
        f.tick();
        assert(fm_linux_input_state(f.wire.task) == 2 && fm_linux_input_ticks(f.wire.task) == 2);
        assert(fm_linux_input_completed(f.wire.task) == 1 && f.emitter.up == 1 && !f.emitter.held);
        assert(f.session.frontend() == 0 && !f.session.owned() && !f.lifetime.owned());
        assert(f.session.start({}, getpid(), second.descriptor, getpid(), caller, f.emitter) == 0);
        f.session.cancel();
        assert(f.session.frontend() == 0 && fm_linux_input_state(second.task) == 3);
        assert(fm_linux_input_state(f.wire.task) == 2); // Replacement does not overwrite a previous result.
    }
    {
        Fixture f;
        f.start();
        const auto cookie = f.session.before(current.source, caller);
        assert(cookie > 1 && f.emitter.held);
        std::thread canceler([&] { f.session.cancel(); });
        canceler.join();
        assert(f.session.frontend() == EBUSY && f.emitter.up == 0);
        f.session.after(current.source, cookie);
        f.emitter.preventUp = true;
        assert(f.session.frontend() == EBUSY && f.session.owned() && f.lifetime.owned());
        assert(fm_linux_input_state(f.wire.task) == 1);
        f.emitter.preventUp = false;
        assert(f.session.frontend() == 0 && !f.session.owned() && !f.lifetime.owned());
        assert(fm_linux_input_state(f.wire.task) == 3 && fm_linux_input_ticks(f.wire.task) == 1);
        assert(f.emitter.up == 1 && !f.emitter.held && f.wire.task->reason[0]);
    }
    {
        Fixture f;
        f.start();
        const auto cookie = f.session.before(current.source, caller);
        assert(cookie > 1);
        f.session.aborted(current.source, cookie);
        assert(f.emitter.up == 0 && fm_linux_input_ticks(f.wire.task) == 0);
        assert(f.session.frontend() == 0 && f.emitter.up == 1 && fm_linux_input_state(f.wire.task) == 3);
    }
    {
        Fixture f;
        f.start();
        f.tick();
        f.lifetime.retired(current.view);
        assert(f.session.frontend() == 0 && f.emitter.up == 1 && fm_linux_input_state(f.wire.task) == 3);
    }
    for (unsigned invalid = 0; invalid < 5; ++invalid) {
        Fixture f;
        if (invalid == 0)
            f.wire.task->count = FM_LINUX_INPUT_STEPS + 1;
        if (invalid == 1)
            f.wire.task->operations[0].ticks = 0;
        if (invalid == 2)
            f.wire.task->operations[0].buttons[0] = {1, 6};
        if (invalid == 3)
            f.wire.task->operations[0].count = FM_LINUX_INPUT_BUTTONS + 1;
        if (invalid == 4)
            f.wire.task->reserved = 1;
        assert(f.session.start({}, getpid(), f.wire.descriptor, getpid(), caller, f.emitter) == EINVAL);
        assert(!f.session.owned() && !f.lifetime.owned() && fm_linux_input_state(f.wire.task) == 0);
        assert(!f.emitter.down && !f.emitter.up);
    }
    {
        Fixture f;
        fm_linux_input_cancel(f.wire.task);
        assert(f.session.start({}, getpid(), f.wire.descriptor, getpid(), caller, f.emitter) == ECANCELED);
        assert(!f.session.owned() && !f.lifetime.owned() && !f.emitter.down);
    }
    {
        Fixture f;
        f.start();
        f.tick();
        f.session.cancel();
        failClose = true;
        assert(f.session.frontend() == EIO && !failClose);
        assert(f.session.owned() && f.lifetime.owned() && f.emitter.up == 1);
        assert(fm_linux_input_state(f.wire.task) == 3);
        // The resident view was already unmapped; callbacks can only forward or retry resource cleanup.
        assert(f.session.before(current.source, caller) == 0);
        assert(f.session.frontend() == 0 && !f.session.owned() && !f.lifetime.owned());
        assert(f.emitter.up == 1);
    }
    {
        Fixture f;
        f.wire.task->count = 0;
        assert(f.session.start({}, getpid(), f.wire.descriptor, 0, 0, f.emitter) == 0);
        assert(fm_linux_input_state(f.wire.task) == 2 && !f.session.owned() && !f.lifetime.owned());
        assert(!f.emitter.down && !f.emitter.up);
    }
    {
        Fixture f;
        int ready[2], finish[2];
        assert(pipe2(ready, O_CLOEXEC) == 0 && pipe2(finish, O_CLOEXEC) == 0);
        const pid_t child = fork();
        assert(child >= 0);
        if (!child) {
            close(ready[0]);
            close(finish[1]);
            f.wire.task->ownerPid = getpid();
            const char signal = 1;
            assert(write(ready[1], &signal, 1) == 1);
            char received;
            assert(read(finish[0], &received, 1) == 1);
            _exit(0);
        }
        close(ready[1]);
        close(finish[0]);
        char signal;
        assert(read(ready[0], &signal, 1) == 1);
        assert(f.session.start({}, child, f.wire.descriptor, getpid(), caller, f.emitter) == 0);
        f.tick();
        assert(f.emitter.held);
        assert(write(finish[1], &signal, 1) == 1);
        int status;
        assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
        assert(f.session.frontend() == 0 && !f.session.owned() && !f.lifetime.owned());
        assert(fm_linux_input_state(f.wire.task) == 3 && f.emitter.up == 1 && !f.emitter.held);
        close(ready[0]);
        close(finish[1]);
    }
}
