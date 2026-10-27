#include "input_task_mapping.h"
#include <cerrno>
#include <fcntl.h>
#include <initializer_list>
#include <linux/memfd.h>
#include <poll.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <utility>

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

int InputTaskMapping::bindOwner(pid_t owner) {
    if (owned())
        return EALREADY;
    if (owner <= 0)
        return EINVAL;
    // Pin liveness before admission so PID reuse cannot revive an abandoned task.
    owner_ = static_cast<int>(syscall(SYS_pidfd_open, owner, 0));
    ownerPid_ = owner;
    return owner_ < 0 ? errno : 0;
}

int InputTaskMapping::create(pid_t owner) {
    if (const int error = bindOwner(owner))
        return error;
    descriptor_ = static_cast<int>(syscall(SYS_memfd_create, "factorio-mcp-input", MFD_CLOEXEC | MFD_ALLOW_SEALING));
    if (descriptor_ < 0 || ftruncate(descriptor_, sizeof(FmLinuxInputTask)) ||
        fcntl(descriptor_, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_SEAL))
        return errno;
    if (const int error = map())
        return error;
    task_->ownerPid = static_cast<uint32_t>(owner);
    task_->targetPid = static_cast<uint32_t>(getpid());
    return validateOwner();
}

int InputTaskMapping::take(InputTaskMapping &source) {
    if (owned())
        return EALREADY;
    if (!source.task_ || source.descriptor_ < 0 || source.owner_ < 0)
        return EINVAL;
    if (const int error = source.validateOwner())
        return error;
    descriptor_ = std::exchange(source.descriptor_, -1);
    owner_ = std::exchange(source.owner_, -1);
    ownerPid_ = std::exchange(source.ownerPid_, 0);
    task_ = std::exchange(source.task_, nullptr);
    return 0;
}

int InputTaskMapping::map() {
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
    return 0;
}

int InputTaskMapping::validateOwner() const {
    bool alive;
    if (const int error = ownerAlive(alive))
        return error;
    if (!alive)
        return ESRCH;
    if (task_->ownerPid != static_cast<uint32_t>(ownerPid_) || task_->targetPid != static_cast<uint32_t>(getpid()))
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
    ownerPid_ = 0;
    return failure;
}
