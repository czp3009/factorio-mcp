#include "input_task_mapping.h"
#include <cassert>
#include <cerrno>
#include <fcntl.h>
#include <linux/memfd.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

namespace {
struct Fixture {
    int descriptor;
    FmLinuxInputTask *task;
    size_t size;

    explicit Fixture(bool seal = true, size_t extent = sizeof(FmLinuxInputTask)) : size(extent) {
        descriptor =
            static_cast<int>(syscall(SYS_memfd_create, "factorio-mcp-input-fixture", MFD_CLOEXEC | MFD_ALLOW_SEALING));
        assert(descriptor >= 0 && ftruncate(descriptor, static_cast<off_t>(size)) == 0);
        if (seal)
            assert(fcntl(descriptor, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW) == 0);
        void *view = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, descriptor, 0);
        assert(view != MAP_FAILED);
        task = static_cast<FmLinuxInputTask *>(view);
        task->ownerPid = task->targetPid = static_cast<uint32_t>(getpid());
    }

    ~Fixture() {
        assert(munmap(task, size) == 0);
        if (descriptor >= 0)
            assert(close(descriptor) == 0);
    }
};
} // namespace

int main() {
    {
        Fixture fixture;
        InputTaskMapping mapping;
        assert(mapping.open(getpid(), fixture.descriptor) == 0 && mapping.owned());
        assert(mapping.open(getpid(), fixture.descriptor) == EALREADY);
        bool alive = false;
        assert(mapping.ownerAlive(alive) == 0 && alive);
        assert(close(fixture.descriptor) == 0);
        fixture.descriptor = -1;
        fm_linux_input_cancel(fixture.task);
        assert(fm_ipc_load(&mapping.task()->cancel) == 1);
        fm_ipc_store(&mapping.task()->completedEntries, 3);
        __atomic_store_n(&mapping.task()->evaluatedTicks, uint64_t{91}, __ATOMIC_RELEASE);
        fm_ipc_store(&mapping.task()->state, 2);
        assert(fm_linux_input_completed(fixture.task) == 3 && fm_linux_input_ticks(fixture.task) == 91);
        assert(fm_linux_input_state(fixture.task) == 2);
        assert(mapping.close() == 0 && !mapping.owned() && mapping.task() == nullptr);
        assert(mapping.close() == 0 && mapping.ownerAlive(alive) == EINVAL && !alive);
    }
    for (unsigned invalid = 0; invalid < 4; ++invalid) {
        Fixture fixture(invalid != 0, sizeof(FmLinuxInputTask) + (invalid == 1 ? 1 : 0));
        if (invalid == 2)
            ++fixture.task->ownerPid;
        if (invalid == 3)
            ++fixture.task->targetPid;
        InputTaskMapping mapping;
        assert(mapping.open(getpid(), fixture.descriptor) == EINVAL && mapping.owned());
        assert(mapping.close() == 0 && !mapping.owned());
    }
    {
        InputTaskMapping mapping;
        assert(mapping.open(0, 0) == EINVAL && !mapping.owned());
        assert(mapping.open(getpid(), -1) == EINVAL && !mapping.owned());
    }
    {
        Fixture fixture;
        int ready[2], finish[2];
        assert(pipe2(ready, O_CLOEXEC) == 0 && pipe2(finish, O_CLOEXEC) == 0);
        const pid_t child = fork();
        assert(child >= 0);
        if (child == 0) {
            close(ready[0]);
            close(finish[1]);
            fixture.task->ownerPid = static_cast<uint32_t>(getpid());
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
        InputTaskMapping mapping;
        assert(mapping.open(child, fixture.descriptor) == 0);
        bool alive;
        assert(mapping.ownerAlive(alive) == 0 && alive);
        assert(write(finish[1], &signal, 1) == 1);
        int status;
        assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
        assert(mapping.ownerAlive(alive) == 0 && !alive);
        // Resident storage remains valid after owner exit, allowing release cleanup and a terminal result.
        fm_ipc_store(&mapping.task()->state, 3);
        assert(fm_linux_input_state(fixture.task) == 3);
        assert(mapping.close() == 0 && !mapping.owned());
        close(ready[0]);
        close(finish[1]);
    }
}
