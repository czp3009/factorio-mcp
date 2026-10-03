#include "resident.h"
#include "linux_ipc.h"
#include <atomic>
#include <cassert>
#include <cerrno>
#include <cstring>
#include <sys/mman.h>
#include <thread>
#include <unistd.h>

namespace {
class FixtureGui {
public:
    uintptr_t caller = 0;
    virtual void logic(bool flag) {
        assert(flag);
        caller = reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    }
};

struct FixtureWorker;
class FixtureListener {
public:
    uintptr_t caller = 0;
    unsigned completed = 0;
    virtual void complete(FixtureWorker *worker);
};

struct FixtureWorker {
    uintptr_t padding[FIXTURE_PADDING];
    FixtureListener *listener;
};

void FixtureListener::complete(FixtureWorker *worker) {
    assert(worker->listener == this);
    caller = reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    ++completed;
}

__attribute__((noinline)) void invokeGui(FixtureGui *gui) {
    asm volatile("" : "+r"(gui) : : "memory");
    gui->logic(true);
    asm volatile("" : : : "memory");
}

__attribute__((noinline)) void invokeWorker(FixtureListener *listener, FixtureWorker *worker) {
    asm volatile("" : "+r"(listener) : : "memory");
    listener->complete(worker);
    asm volatile("" : : : "memory");
}
} // namespace

int main() {
    alarm(30);
    assert(fm_linux_initialize() >= 0);
    auto *shared = reinterpret_cast<FmLinuxShared *>(fm_linux_resident.mapping);
    FixtureGui gui;
    FixtureGui *instance = &gui;
    invokeGui(&gui);
    const auto caller = gui.caller;
    FixtureListener listener;
    FixtureWorker worker{{}, &listener};
    std::atomic<unsigned> requested{1}, returned{0};
    std::atomic<bool> stopped{false};
    std::thread owner([&] {
        while (!stopped.load()) {
            if (returned.load() < requested.load()) {
                invokeWorker(&listener, &worker);
                returned.fetch_add(1);
            } else
                std::this_thread::yield();
        }
    });
    while (returned.load() != 1)
        std::this_thread::yield();
    const auto workerCaller = listener.caller;
    uintptr_t *originalGui, *originalListener;
    std::memcpy(&originalGui, static_cast<const void *>(&gui), sizeof(originalGui));
    std::memcpy(&originalListener, static_cast<const void *>(&listener), sizeof(originalListener));
    const size_t pageSize = sysconf(_SC_PAGESIZE);
    auto *page = static_cast<uintptr_t *>(mmap(nullptr, pageSize, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    assert(page != MAP_FAILED);
    std::memcpy(page, originalGui - 2, 3 * sizeof(uintptr_t));
    std::memcpy(page + 8, originalListener - 2, 3 * sizeof(uintptr_t));
    auto *guiTable = page + 2;
    auto *listenerTable = page + 10;
    std::memcpy(static_cast<void *>(&gui), &guiTable, sizeof(guiTable));
    std::memcpy(static_cast<void *>(&listener), &listenerTable, sizeof(listenerTable));
    assert(mprotect(page, pageSize, PROT_READ) == 0);
    shared->config = {reinterpret_cast<uintptr_t>(guiTable), originalGui[0], reinterpret_cast<uintptr_t>(&instance),
        caller, caller - 1, caller + 1, PROT_READ};
    assert(fm_linux_attach() == 0);
    shared->workerConfig = {{reinterpret_cast<uintptr_t>(listenerTable), originalListener[-1], workerCaller,
        sizeof(worker), offsetof(FixtureWorker, listener)}, reinterpret_cast<uintptr_t>(listenerTable),
        originalListener[0], PROT_READ};

    auto request = [&](uint32_t operation, bool canceled = false) {
        assert(fm_ipc_load(&shared->command) == FM_LINUX_IDLE);
        shared->operation = operation;
        fm_ipc_store(&shared->cancel, canceled);
        fm_ipc_store(&shared->command, FM_LINUX_PENDING);
        invokeGui(&gui);
        assert(fm_ipc_load(&shared->command) == FM_LINUX_COMPLETE);
        const int result = shared->result;
        fm_ipc_store(&shared->command, FM_LINUX_IDLE);
        return result;
    };
    assert(request(FM_LINUX_WORKER, true) == -ECANCELED && listenerTable[0] == originalListener[0]);
    shared->workerConfig.layout.listenerMember = sizeof(worker);
    assert(request(FM_LINUX_WORKER) == -EINVAL && listenerTable[0] == originalListener[0]);
    shared->workerConfig.layout.listenerMember = offsetof(FixtureWorker, listener);
    assert(request(FM_LINUX_WORKER) == 0 && listenerTable[0] != originalListener[0]);
    const auto wrapper = listenerTable[0];
    auto completeWorker = [&] {
        const auto next = requested.fetch_add(1) + 1;
        while (returned.load() != next)
            std::this_thread::yield();
        assert(listener.caller == workerCaller && listener.completed == next);
    };
    completeWorker();
    assert(fm_ipc_load(&shared->workerThread) && fm_ipc_load(&shared->workerThread) != static_cast<uint32_t>(getpid()));
    assert(!fm_ipc_load(&shared->workerFailure));
    assert(request(FM_LINUX_INPUT) == -EINVAL && !fm_ipc_load(&shared->inputOwned));
    assert(request(FM_LINUX_INPUT_CANCEL) == 0 && !fm_ipc_load(&shared->inputOwned));
    const auto thread = fm_ipc_load(&shared->workerThread);
    assert(request(FM_LINUX_WORKER) == 0);
    ++shared->workerConfig.layout.caller;
    assert(request(FM_LINUX_WORKER) == -ESTALE);
    --shared->workerConfig.layout.caller;
    completeWorker();
    assert(fm_ipc_load(&shared->workerThread) == thread && !fm_ipc_load(&shared->workerFailure));
    assert(mprotect(page, pageSize, PROT_READ | PROT_WRITE) == 0);
    listenerTable[0] = originalListener[0];
    assert(mprotect(page, pageSize, PROT_READ) == 0);
    assert(request(FM_LINUX_DETACH, true) == -ESTALE && shared->attached && shared->pointerOwned);
    assert(mprotect(page, pageSize, PROT_READ | PROT_WRITE) == 0);
    listenerTable[0] = wrapper;
    assert(mprotect(page, pageSize, PROT_READ) == 0);
    assert(request(FM_LINUX_DETACH, true) == 0);
    assert(!shared->attached && !shared->pointerOwned && !shared->protectionOwned);
    assert(listenerTable[0] == originalListener[0] && guiTable[0] == originalGui[0]);
    assert(fm_linux_cleanup() == 0);
    assert(fm_linux_attach() == 0 && request(FM_LINUX_WORKER) == 0);
    completeWorker();
    assert(fm_ipc_load(&shared->workerThread) == thread && !fm_ipc_load(&shared->workerFailure));
    assert(request(FM_LINUX_DETACH, true) == 0);
    stopped.store(true);
    owner.join();
    std::memcpy(static_cast<void *>(&listener), &originalListener, sizeof(originalListener));
    std::memcpy(static_cast<void *>(&gui), &originalGui, sizeof(originalGui));
    assert(munmap(page, pageSize) == 0);
}
