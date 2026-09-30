#include <cerrno>
#include <csignal>
#include <cstdint>
#include <dlfcn.h>
#include <linux/memfd.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

extern "C" __attribute__((noinline)) void fm_fixture_safe_point() {
    // Observable code prevents the compiler from eliminating the test call.
    asm volatile("" ::: "memory");
}

extern "C" __attribute__((noinline)) uint64_t fm_fixture_remote(
    uint64_t a, uint64_t b, uint64_t c, uint64_t d, uint64_t e, uint64_t f) {
    asm volatile("pxor %%xmm0, %%xmm0\npxor %%xmm15, %%xmm15" ::: "xmm0", "xmm15");
    return a + b * 3 + c * 5 + d * 7 + e * 11 + f * 13;
}

extern "C" __attribute__((noinline)) uint64_t fm_fixture_remote_wait() {
    if (write(STDOUT_FILENO, "w", 1) != 1)
        return 0;
    char command{};
    ssize_t count;
    do {
        count = read(STDIN_FILENO, &command, 1);
    } while (count < 0 && errno == EINTR);
    return count == 1 && command == 'g' ? 77 : 0;
}

static void signalReceived(int signal) {
    const int saved = errno;
    const char value = signal == SIGUSR1 ? 's' : 't';
    const auto ignored = write(STDOUT_FILENO, &value, 1);
    (void)ignored;
    errno = saved;
}

int main() {
    if (pthread_self() == 0)
        return 10;
    (void)dlerror();
    const int codeFile = syscall(SYS_memfd_create, "factorio-mcp-nonelf-test", MFD_CLOEXEC);
    if (codeFile < 0 || ftruncate(codeFile, 4096) != 0)
        return 8;
    void *code = mmap(nullptr, 4096, PROT_READ | PROT_EXEC, MAP_SHARED, codeFile, 0);
    if (code == MAP_FAILED)
        return 9;
    // This watchdog belongs only to the automatic test fixture.
    alarm(30);
    struct sigaction action {};
    action.sa_handler = signalReceived;
    action.sa_flags = SA_RESTART;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGUSR1, &action, nullptr) != 0 || sigaction(SIGTRAP, &action, nullptr) != 0)
        return 1;
    if (write(STDOUT_FILENO, "r", 1) != 1)
        return 2;
    for (;;) {
        char command{};
        const auto count = read(STDIN_FILENO, &command, 1);
        if (count < 0 && errno == EINTR)
            continue;
        if (count != 1)
            return 3;
        if (command == 'x')
            return 0;
        if (command == 'p') {
            if (write(STDOUT_FILENO, "p", 1) != 1)
                return 4;
        } else if (command == 'u') {
            raise(SIGUSR1);
        } else if (command == 't') {
            raise(SIGTRAP);
        } else if (command == 'b') {
            asm volatile("pcmpeqb %%xmm0, %%xmm0\npcmpeqb %%xmm15, %%xmm15" ::: "xmm0", "xmm15");
            fm_fixture_safe_point();
            if (write(STDOUT_FILENO, "b", 1) != 1)
                return 6;
        } else {
            return 5;
        }
    }
}
