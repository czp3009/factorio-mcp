#include "targeter.h"
#include "targeter_fixture.h"
#include <array>
#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <new>
#include <sys/mman.h>
#include <unistd.h>

static void require(bool value, const char *message) {
    if (!value) {
        std::fprintf(stderr, "factorio-mcp targeter fixture: %s\n", message);
        std::abort();
    }
}

static unsigned calls = 0;

static void noCleanup(void *, void *) {
    ++calls;
}

static void throws(void *, void *) {
    ++calls;
    throw std::runtime_error("fixture failure");
}

static void partialCleanup(void *pointer, void *) {
    ++calls;
    static_cast<Targeter *>(pointer)->target = nullptr;
}

static void attachThenThrow(void *pointer, void *target) {
    ++calls;
    static_cast<Targeter *>(pointer)->attachTo(static_cast<Targetable *>(target));
    throw std::runtime_error("fixture failure after attachment");
}

int main() {
    alarm(30);
    const auto method = &Targeter::attachTo;
    std::array<ptrdiff_t, 2> representation;
    static_assert(sizeof(method) == sizeof(representation));
    std::memcpy(representation.data(), &method, sizeof(method));
    require(representation[0] > 0 && !(representation[0] & 1) && representation[1] == 0,
            "unexpected fixture member pointer");
    const FmLinuxTargeterLayout layout{static_cast<uint64_t>(representation[0]), sizeof(Targeter), sizeof(Targetable),
        offsetof(Targeter, target), offsetof(Targeter, previous), offsetof(Targeter, next), offsetof(Targetable, head)};
    require(validTargeterLayout(layout), "valid compiler layout rejected");
    auto malformed = layout;
    malformed.previous = malformed.next;
    require(!validTargeterLayout(malformed), "overlapping links accepted");
    malformed = layout;
    malformed.head = malformed.targetableExtent;
    require(!validTargeterLayout(malformed), "out-of-bounds head accepted");
    malformed = layout;
    malformed.target += 1;
    require(!validTargeterLayout(malformed), "unaligned pointer field accepted");

    Targetable owner;
    Targeter first, middle, last;
    const auto ownerAddress = reinterpret_cast<uintptr_t>(&owner);
    require(attachFreshTargeter(reinterpret_cast<uintptr_t>(&first), ownerAddress, layout) == 0,
            "cannot register first owned targeter");
    require(attachFreshTargeter(reinterpret_cast<uintptr_t>(&middle), ownerAddress, layout) == 0,
            "cannot register owned targeter before an existing head");
    require(attachFreshTargeter(reinterpret_cast<uintptr_t>(&last), ownerAddress, layout) == 0,
            "cannot register third owned targeter");
    require(attachFreshTargeter(reinterpret_cast<uintptr_t>(&last), ownerAddress, layout) == EALREADY,
            "registered targeter was reassigned");
    const auto address = reinterpret_cast<uintptr_t>(&middle);
    TargeterLinks links;
    require(readTargeter(address, layout, links) == 0 && links.target == reinterpret_cast<uintptr_t>(&owner) &&
            links.previous == reinterpret_cast<uintptr_t>(&last) && links.next == reinterpret_cast<uintptr_t>(&first),
            "linked targeter did not preserve native references");
    require(releaseTargeter(address, layout) == 0 && last.next == &first && first.previous == &last &&
            !middle.target && !middle.previous && !middle.next, "native middle unlink failed");
    malformed = layout;
    malformed.release = reinterpret_cast<uintptr_t>(&noCleanup);
    calls = 0;
    require(releaseTargeter(address, malformed) == 0 && !calls, "empty targeter invoked native cleanup again");
    require(releaseTargeter(reinterpret_cast<uintptr_t>(&last), layout) == 0 && owner.head == &first && !first.previous,
            "native head unlink failed");
    require(releaseTargeter(reinterpret_cast<uintptr_t>(&first), layout) == 0 && !owner.head,
            "native final unlink failed");

    calls = 0;
    require(attachFreshTargeter(address, ownerAddress, malformed) == EPROTO && calls == 1,
            "ineffective attachment reported success");
    malformed.release = reinterpret_cast<uintptr_t>(&attachThenThrow);
    require(attachFreshTargeter(address, ownerAddress, malformed) == EFAULT && middle.target == &owner,
            "uncertain attachment lost its registered ownership");
    require(attachFreshTargeter(address, ownerAddress, malformed) == EALREADY && calls == 2,
            "uncertain attachment was replayed");
    require(releaseTargeter(address, layout) == 0 && !owner.head,
            "uncertain attachment could not be cleaned up");
    malformed.release = reinterpret_cast<uintptr_t>(&noCleanup);

    first.attachTo(&owner);
    middle.attachTo(&owner);
    last.attachTo(&owner);
    calls = 0;
    require(releaseTargeter(address, malformed) == EPROTO && calls == 1 && middle.target == &owner,
            "ineffective cleanup reported success");
    malformed.release = reinterpret_cast<uintptr_t>(&throws);
    require(releaseTargeter(address, malformed) == EFAULT && middle.target == &owner,
            "exception escaped or discarded live ownership");
    malformed.release = reinterpret_cast<uintptr_t>(&partialCleanup);
    calls = 0;
    require(releaseTargeter(address, malformed) == EPROTO && calls == 1,
            "partial cleanup reported success");
    require(releaseTargeter(address, malformed) == EPROTO && calls == 1,
            "malformed partial cleanup was replayed");
    middle.target = &owner;
    malformed.release = reinterpret_cast<uintptr_t>(&noCleanup);
    calls = 0;
    last.next = nullptr;
    require(releaseTargeter(address, malformed) == EPROTO && !calls, "broken backward link entered native cleanup");
    last.next = &middle;
    middle.next = &middle;
    require(releaseTargeter(address, malformed) == ELOOP && !calls, "self-linked targeter entered native cleanup");
    middle.next = &first;
    owner.head = &middle;
    require(releaseTargeter(address, malformed) == EPROTO && !calls, "incorrect list head entered native cleanup");
    owner.head = &last;

    const long pageSize = sysconf(_SC_PAGESIZE);
    require(pageSize > 0, "missing page size");
    void *guard = mmap(nullptr, static_cast<size_t>(pageSize), PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    require(guard != MAP_FAILED, "cannot allocate guard page");
    middle.next = static_cast<Targeter *>(guard);
    require(releaseTargeter(address, malformed) == EFAULT && !calls, "unreadable neighbor entered native cleanup");
    middle.next = &first;
    middle.target = static_cast<Targetable *>(guard);
    require(releaseTargeter(address, malformed) == EFAULT && !calls, "unreadable target entered native cleanup");
    middle.target = &owner;
    require(readTargeter(reinterpret_cast<uintptr_t>(guard), layout, links) == EFAULT && !links.target,
            "unreadable targeter published a borrowed pointer");
    require(munmap(guard, static_cast<size_t>(pageSize)) == 0, "guard page cleanup failed");
    require(releaseTargeter(address, layout) == 0, "cannot clean up after rejecting malformed links");
    require(releaseTargeter(reinterpret_cast<uintptr_t>(&first), layout) == 0 && !last.next,
            "native tail unlink failed");
    require(releaseTargeter(reinterpret_cast<uintptr_t>(&last), layout) == 0 && !owner.head,
            "final list cleanup failed");

    TargetReference ownedFirst, ownedSecond;
    uintptr_t borrowed = 1;
    require(ownedFirst.borrow(borrowed) == 0 && !borrowed && !ownedFirst.owned(), "empty reference is not empty");
    require(ownedFirst.attach(ownerAddress, layout) == 0 && ownedFirst.owned(), "owned reference registration failed");
    require(ownedSecond.attach(ownerAddress, layout) == 0 && ownedSecond.owned(), "second reference registration failed");
    require(ownedFirst.borrow(borrowed) == 0 && borrowed == ownerAddress, "owned reference lost its native target");
    owner.clear();
    require(ownedFirst.borrow(borrowed) == 0 && !borrowed && ownedFirst.owned(),
            "native clear did not invalidate the first reference while retaining storage ownership");
    require(ownedSecond.borrow(borrowed) == 0 && !borrowed && ownedSecond.owned(),
            "native clear did not invalidate the second reference");
    owner.~Targetable();
    new (&owner) Targetable;
    first.attachTo(&owner);
    require(ownedFirst.borrow(borrowed) == 0 && !borrowed, "address reuse revived an old reference");
    require(ownedFirst.attach(ownerAddress, layout) == EALREADY, "cleared but owned reference was overwritten");
    require(ownedFirst.release() == 0 && !ownedFirst.owned() && ownedFirst.release() == 0,
            "cleared reference could not release ownership idempotently");
    require(ownedSecond.release() == 0 && owner.head == &first, "old reference release changed the replacement object");
    first.attachTo(nullptr);
    require(ownedFirst.attach(ownerAddress, layout) == 0 && ownedFirst.release() == 0 && !owner.head,
            "released storage could not be registered again");

    malformed = layout;
    malformed.release = reinterpret_cast<uintptr_t>(&attachThenThrow);
    require(ownedFirst.attach(ownerAddress, malformed) == EFAULT && ownedFirst.owned(),
            "uncertain registration discarded owned storage");
    require(ownedFirst.borrow(borrowed) == 0 && borrowed == ownerAddress,
            "uncertain registration discarded the native reference");
    // Clear via the native owner before release; the throwing entry must not be called again.
    owner.clear();
    require(ownedFirst.release() == 0 && !ownedFirst.owned(), "cleared uncertain registration cannot release ownership");
}
