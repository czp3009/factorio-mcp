#include "input_task_mapping.h"
#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <initializer_list>
#include <linux/memfd.h>
#include <poll.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

InputTaskMapping::~InputTaskMapping() {
    close();
}

int InputTaskMapping::ownerAlive(bool &alive) const {
    alive = false;
    if (owner_ < 0)
        return EINVAL;
    pollfd handle{owner_, POLLIN, 0};
    const int result = poll(&handle, 1, 0);
    if (result < 0)
        return errno;
    if (handle.revents & (POLLERR | POLLNVAL))
        return EIO;
    alive = result == 0;
    return 0;
}

int InputTaskMapping::open(pid_t owner, int descriptor) {
    if (owned())
        return EALREADY;
    if (owner <= 0 || descriptor < 0)
        return EINVAL;
    // Bind the process before opening its proc descriptor. If this PID is recycled during admission,
    // the bound pidfd becomes readable and rejects the replacement process's mapping below.
    owner_ = static_cast<int>(syscall(SYS_pidfd_open, owner, 0));
    if (owner_ < 0)
        return errno;
    char path[96];
    const int length = std::snprintf(path, sizeof(path), "/proc/%d/fd/%d", owner, descriptor);
    if (length <= 0 || static_cast<size_t>(length) >= sizeof(path))
        return EINVAL;
    descriptor_ = ::open(path, O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (descriptor_ < 0)
        return errno;
    struct stat metadata{};
    if (fstat(descriptor_, &metadata))
        return errno;
    if (!S_ISREG(metadata.st_mode) || metadata.st_size != static_cast<off_t>(sizeof(FmLinuxInputTask)) ||
        metadata.st_uid != geteuid())
        return EINVAL;
    const int seals = fcntl(descriptor_, F_GET_SEALS);
    if (seals < 0)
        return errno;
    if ((seals & (F_SEAL_SHRINK | F_SEAL_GROW)) != (F_SEAL_SHRINK | F_SEAL_GROW))
        return EINVAL;
    void *view = mmap(nullptr, sizeof(FmLinuxInputTask), PROT_READ | PROT_WRITE, MAP_SHARED, descriptor_, 0);
    if (view == MAP_FAILED)
        return errno;
    task_ = static_cast<FmLinuxInputTask *>(view);
    bool alive;
    if (const int error = ownerAlive(alive))
        return error;
    if (!alive)
        return ESRCH;
    if (task_->ownerPid != static_cast<uint32_t>(owner) || task_->targetPid != static_cast<uint32_t>(getpid()))
        return EINVAL;
    return 0;
}

int InputTaskMapping::close() {
    if (task_) {
        if (munmap(task_, sizeof(FmLinuxInputTask)))
            return errno;
        task_ = nullptr;
    }
    int failure = 0;
    for (int *handle : {&descriptor_, &owner_}) {
        if (*handle < 0)
            continue;
        const int descriptor = *handle;
        *handle = -1;
        // Linux releases the descriptor even on EINTR; never retry a possibly recycled descriptor.
        if (::close(descriptor) && errno != EINTR && !failure)
            failure = errno;
    }
    return failure;
}
