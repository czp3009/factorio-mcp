#include "frame_hook_owner.h"
#include <cassert>
#include <cerrno>
#include <sys/mman.h>
#include <thread>
#include <unistd.h>

namespace {
bool denyRestore;
int protect(void *address, size_t size, int flags) {
    if (denyRestore && flags == PROT_READ) {
        errno = EACCES;
        return -1;
    }
    return mprotect(address, size, flags);
}
} // namespace

int main() {
    const auto pageSize = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    auto *page = static_cast<uintptr_t *>(mmap(nullptr, pageSize, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    assert(page != MAP_FAILED);
    page[2] = 0x1000;
    uintptr_t device = reinterpret_cast<uintptr_t>(page);
    const FmLinuxFrameHookSite site{reinterpret_cast<uintptr_t>(&device), device, page[2],
        static_cast<uint32_t>(pageSize), 2 * sizeof(uintptr_t), PROT_READ};
    assert(mprotect(page, pageSize, PROT_READ) == 0);
    uint64_t forward = 0;
    FrameHookOwner owner(protect, forward);
    std::thread worker([&] {
        assert(owner.install(site, 0x2000, pageSize) == EPERM);
        assert(owner.remove() == EPERM);
    });
    worker.join();
    auto invalid = site;
    invalid.member = invalid.deviceSize;
    assert(owner.install(invalid, 0x2000, pageSize) == EINVAL && !owner.hasOwnership());
    device = 0;
    assert(owner.install(site, 0x2000, pageSize) == ESTALE && !forward);
    device = site.device;
    denyRestore = true;
    assert(owner.install(site, 0x2000, pageSize) == EACCES);
    assert(owner.ownsPointer() && owner.ownsProtection() && page[2] == 0x2000 && forward == site.original);
    assert(owner.install(site, 0x2000, pageSize) == EBUSY);
    assert(owner.remove() == EACCES && !owner.ownsPointer() && owner.ownsProtection());
    assert(page[2] == site.original);
    denyRestore = false;
    assert(owner.remove() == 0 && !owner.hasOwnership());
    assert(owner.install(site, 0x2000, pageSize) == 0);
    assert(owner.install(site, 0x2000, pageSize) == 0);
    device = 0;
    assert(owner.remove() == ESTALE && owner.ownsPointer() && page[2] == 0x2000);
    device = site.device;
    assert(mprotect(page, pageSize, PROT_READ | PROT_WRITE) == 0);
    page[2] = 0x3000;
    assert(mprotect(page, pageSize, PROT_READ) == 0);
    assert(owner.remove() == ESTALE && owner.ownsPointer());
    assert(owner.install(site, 0x2000, pageSize) == ESTALE);
    assert(mprotect(page, pageSize, PROT_READ | PROT_WRITE) == 0);
    page[2] = 0x2000;
    assert(mprotect(page, pageSize, PROT_READ) == 0);
    assert(owner.remove() == 0 && page[2] == site.original && owner.remove() == 0);
    invalid = site;
    invalid.original = 0x3000;
    assert(owner.install(invalid, 0x2000, pageSize) == ESTALE && forward == site.original);
    assert(owner.install(site, 0x2000, pageSize) == 0);
    assert(munmap(page, pageSize) == 0);
    // A vanished allocation is not dereferenced or treated as successfully unhooked. Process exit owns
    // final reclamation when the original allocation cannot be revalidated for cooperative cleanup.
    assert(owner.remove() == EFAULT && owner.hasOwnership());
}
