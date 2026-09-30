#include "ipc_fixture.h"
#include "linux_ipc.h"
#include "linux_storage.h"
#include <cerrno>
#include <cstdio>
#include <sys/mman.h>
#include <unistd.h>

int main() {
    alarm(30);
    const int descriptor = syscall(FM_MEMFD_SYSCALL, "factorio-mcp-ipc-fixture", FM_MEMFD_FLAGS);
    if (descriptor < 0 || ftruncate(descriptor, sizeof(FmIpcFixture)) != 0 ||
        syscall(SYS_fcntl, descriptor, FM_ADD_SEALS, FM_SIZE_SEALS) != 0)
        return 1;
    auto *state = static_cast<FmIpcFixture *>(mmap(nullptr, sizeof(FmIpcFixture), PROT_READ | PROT_WRITE,
                                                 MAP_SHARED, descriptor, 0));
    if (state == MAP_FAILED || write(STDOUT_FILENO, "r", 1) != 1)
        return 2;
    for (;;) {
        char command{};
        const auto count = read(STDIN_FILENO, &command, 1);
        if (count < 0 && errno == EINTR)
            continue;
        if (count != 1)
            return 3;
        char response{};
        switch (command) {
        case 'd':
            if (dprintf(STDOUT_FILENO, "%d\n", descriptor) < 0)
                return 4;
            continue;
        case 'e':
            if (!fm_ipc_exchange_if(&state->state, 1, 2)) {
                response = 'n';
            } else {
                if (fm_ipc_load(&state->cancel)) {
                    state->result = 0;
                } else {
                    state->result = state->request * 7 + 11;
                    ++state->executions;
                }
                fm_ipc_store(&state->state, 3);
                response = 'e';
            }
            break;
        case 'l':
            if (syscall(SYS_flock, descriptor, LOCK_EX | LOCK_NB) == 0)
                response = 'l';
            else if (errno == EWOULDBLOCK)
                response = 'b';
            else
                return 5;
            break;
        case 'u':
            if (syscall(SYS_flock, descriptor, LOCK_UN) != 0)
                return 6;
            response = 'u';
            break;
        case 'x':
            if (munmap(state, sizeof(FmIpcFixture)) != 0 || close(descriptor) != 0)
                return 7;
            return 0;
        default:
            return 8;
        }
        if (write(STDOUT_FILENO, &response, 1) != 1)
            return 9;
    }
}
