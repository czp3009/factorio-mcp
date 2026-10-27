#include "input_task_mapping.h"
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <unistd.h>

namespace {
void protectedOwner() {
    int ready[2], finish[2];
    assert(pipe2(ready, O_CLOEXEC) == 0 && pipe2(finish, O_CLOEXEC) == 0);
    const pid_t owner = getpid();
    const int dumpable = prctl(PR_GET_DUMPABLE);
    assert(dumpable >= 0 && prctl(PR_SET_DUMPABLE, 0) == 0);
    const pid_t resident = fork();
    assert(resident >= 0);
    if (!resident) {
        assert(prctl(PR_SET_DUMPABLE, 1) == 0);
        close(ready[0]);
        close(finish[1]);
        char path[96];
        std::snprintf(path, sizeof(path), "/proc/%d/fd/%d", owner, ready[0]);
        assert(open(path, O_RDONLY | O_CLOEXEC) == -1 && errno == EACCES);
        InputTaskMapping prepared;
        assert(prepared.create(owner) == 0);
        const int descriptor = prepared.descriptor();
        assert(write(ready[1], &descriptor, sizeof(descriptor)) == sizeof(descriptor));
        char signal;
        assert(read(finish[0], &signal, 1) == 1);
        InputTaskMapping admitted;
        assert(admitted.take(prepared) == 0 && !prepared.owned());
        assert(fm_ipc_load(&admitted.task()->cancel) == 1);
        fm_ipc_store(&admitted.task()->state, 3);
        assert(admitted.close() == 0);
        _exit(0);
    }
    close(ready[1]);
    close(finish[0]);
    int descriptor;
    assert(read(ready[0], &descriptor, sizeof(descriptor)) == sizeof(descriptor));
    char path[96];
    std::snprintf(path, sizeof(path), "/proc/%d/fd/%d", resident, descriptor);
    const int local = open(path, O_RDWR | O_CLOEXEC);
    assert(local >= 0);
    void *view = mmap(nullptr, sizeof(FmLinuxInputTask), PROT_READ | PROT_WRITE, MAP_SHARED, local, 0);
    assert(view != MAP_FAILED);
    auto *task = static_cast<FmLinuxInputTask *>(view);
    assert(task->ownerPid == static_cast<uint32_t>(owner) && task->targetPid == static_cast<uint32_t>(resident));
    fm_linux_input_cancel(task);
    const char signal = 1;
    assert(write(finish[1], &signal, 1) == 1);
    int status;
    assert(waitpid(resident, &status, 0) == resident && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(fm_linux_input_state(task) == 3);
    assert(munmap(view, sizeof(FmLinuxInputTask)) == 0 && close(local) == 0);
    assert(prctl(PR_SET_DUMPABLE, dumpable) == 0);
    close(ready[0]);
    close(finish[1]);
}
} // namespace

int main() {
    {
        InputTaskMapping prepared, admitted;
        assert(prepared.create(getpid()) == 0 && prepared.owned());
        assert(prepared.create(getpid()) == EALREADY);
        assert(ftruncate(prepared.descriptor(), 0) == -1 && errno == EPERM);
        assert(ftruncate(prepared.descriptor(), sizeof(FmLinuxInputTask) + 1) == -1 && errno == EPERM);
        auto *wire = prepared.task();
        assert(admitted.take(prepared) == 0 && !prepared.owned() && admitted.task() == wire);
        assert(admitted.take(prepared) == EALREADY);
        bool alive = false;
        assert(admitted.ownerAlive(alive) == 0 && alive);
        fm_linux_input_cancel(wire);
        assert(fm_ipc_load(&admitted.task()->cancel) == 1);
        fm_ipc_store(&admitted.task()->completedEntries, 3);
        __atomic_store_n(&admitted.task()->evaluatedTicks, uint64_t{91}, __ATOMIC_RELEASE);
        fm_ipc_store(&admitted.task()->state, 2);
        assert(fm_linux_input_completed(wire) == 3 && fm_linux_input_ticks(wire) == 91);
        assert(admitted.close() == 0 && !admitted.owned() && admitted.task() == nullptr);
        assert(admitted.close() == 0 && admitted.ownerAlive(alive) == EINVAL && !alive);
    }
    for (unsigned invalid = 0; invalid < 2; ++invalid) {
        InputTaskMapping prepared, admitted;
        assert(prepared.create(getpid()) == 0);
        if (invalid == 0)
            ++prepared.task()->ownerPid;
        else
            ++prepared.task()->targetPid;
        assert(admitted.take(prepared) == EINVAL && !admitted.owned() && prepared.owned());
        assert(prepared.close() == 0 && !prepared.owned());
    }
    {
        InputTaskMapping prepared, admitted;
        assert(prepared.create(0) == EINVAL && !prepared.owned());
        assert(admitted.take(prepared) == EINVAL && !admitted.owned());
    }
    {
        int finish[2];
        assert(pipe2(finish, O_CLOEXEC) == 0);
        const pid_t child = fork();
        assert(child >= 0);
        if (child == 0) {
            close(finish[1]);
            char signal;
            assert(read(finish[0], &signal, 1) == 1);
            _exit(0);
        }
        close(finish[0]);
        InputTaskMapping prepared, admitted;
        assert(prepared.create(child) == 0 && admitted.take(prepared) == 0);
        bool alive;
        assert(admitted.ownerAlive(alive) == 0 && alive);
        const char signal = 1;
        assert(write(finish[1], &signal, 1) == 1);
        int status;
        assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
        assert(admitted.ownerAlive(alive) == 0 && !alive);
        // The original pidfd and mapping survive transfer and owner exit for terminal cleanup.
        fm_ipc_store(&admitted.task()->state, 3);
        assert(fm_linux_input_state(admitted.task()) == 3);
        assert(prepared.take(admitted) == ESRCH && admitted.owned() && !prepared.owned());
        assert(admitted.close() == 0 && !admitted.owned());
        close(finish[1]);
    }
    protectedOwner();
}
