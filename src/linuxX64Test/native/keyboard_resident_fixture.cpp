#include "resident.h"
#include "linux_ipc.h"
#include <cassert>
#include <cerrno>
#include <cstddef>
#include <cstring>
#include <sys/mman.h>
#include <thread>
#include <unistd.h>

struct Event {
    uint64_t marker = 731;
    uint32_t type = 77;
    double time = 0;
    uint32_t key = 0;
};

class Gui {
public:
    uintptr_t caller = 0;
    virtual void logic() { caller = reinterpret_cast<uintptr_t>(__builtin_return_address(0)); }
};

class Window {
public:
    uintptr_t caller = 0;
    bool real = false;
    virtual bool poll(Event &event) {
        caller = reinterpret_cast<uintptr_t>(__builtin_return_address(0));
        if (real)
            event = {912, 55, 1.25, 123};
        errno = EDOM;
        return real;
    }
};

static uintptr_t outerCaller, stack, returnAddress, eventAddress;

__attribute__((noinline)) static void invoke(Gui *gui) {
    asm volatile("" : "+r"(gui) : : "memory");
    gui->logic();
    asm volatile("" : : : "memory");
}

__attribute__((noinline)) static bool next(Window *window, Event &output) {
    Event event;
    returnAddress = reinterpret_cast<uintptr_t>(__builtin_frame_address(0)) + sizeof(uintptr_t);
    eventAddress = reinterpret_cast<uintptr_t>(&event);
    outerCaller = reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    asm volatile("" : "+r"(window) : : "memory");
    asm volatile("movq %%rsp, %0" : "=m"(stack));
    const bool result = window->poll(event);
    if (result)
        output = event;
    return result;
}

__attribute__((noinline)) static bool pump(Window *window, Event &output) {
    const bool result = next(window, output);
    asm volatile("" : : : "memory");
    return result;
}

__attribute__((noinline)) static bool wrongCaller(Window *window, Event &output) {
    asm volatile("" : "+r"(window) : : "memory");
    const bool result = window->poll(output);
    asm volatile("" : : : "memory");
    return result;
}

static bool failClock;
static uint32_t ticks() {
    if (failClock)
        throw 1;
    return 1250;
}

struct Table {
    void *object;
    uintptr_t *original;
    uintptr_t *addressPoint;
    void *page;
    size_t pageSize;

    explicit Table(void *object) : object(object), pageSize(sysconf(_SC_PAGESIZE)) {
        std::memcpy(&original, object, sizeof(original));
        page = mmap(nullptr, pageSize, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        assert(page != MAP_FAILED);
        auto *words = static_cast<uintptr_t *>(page);
        std::memcpy(words, original - 2, 3 * sizeof(uintptr_t));
        addressPoint = words + 2;
        std::memcpy(object, &addressPoint, sizeof(addressPoint));
        assert(mprotect(page, pageSize, PROT_READ) == 0);
    }

    ~Table() {
        assert(*addressPoint == *original);
        std::memcpy(object, &original, sizeof(original));
        assert(munmap(page, pageSize) == 0);
    }
};

int main() {
    alarm(30);
    assert(fm_linux_initialize() >= 0);
    auto *shared = reinterpret_cast<FmLinuxShared *>(fm_linux_resident.mapping);
    Gui gui;
    Window window;
    Gui *instance = &gui;
    Event event;
    invoke(&gui);
    assert(!pump(&window, event));
    Table guiTable(&gui), windowTable(&window);
    shared->config = {reinterpret_cast<uintptr_t>(guiTable.addressPoint), *guiTable.original,
        reinterpret_cast<uintptr_t>(&instance), gui.caller, gui.caller - 1, gui.caller + 1, PROT_READ};
    shared->pollConfig = {reinterpret_cast<uintptr_t>(windowTable.addressPoint), *windowTable.original,
        reinterpret_cast<uintptr_t>(windowTable.addressPoint), window.caller, outerCaller,
        static_cast<uint32_t>(returnAddress - stack), static_cast<uint32_t>(eventAddress - stack),
        sizeof(Event), PROT_READ};
    shared->keyConfig.event.extent = sizeof(Event);
    shared->keyConfig.event.type = offsetof(Event, type);
    shared->keyConfig.event.time = offsetof(Event, time);
    shared->keyConfig.event.code = offsetof(Event, key);
    shared->keyConfig.event.emptyType = 77;
    shared->keyConfig.event.press = 19;
    shared->keyConfig.event.release = 39;
    shared->keyConfig.clock = {reinterpret_cast<uintptr_t>(&ticks), 1000};
    shared->keyRequest = {2, {11, 17}};
    assert(fm_linux_attach() == 0);
    auto request = [&](uint32_t operation) {
        assert(shared->command == FM_LINUX_IDLE);
        shared->operation = operation;
        shared->cancel = 0;
        fm_ipc_store(&shared->command, FM_LINUX_PENDING);
        invoke(&gui);
    };
    auto consume = [&](int result) {
        assert(shared->command == FM_LINUX_COMPLETE && shared->result == -result);
        assert(shared->resultFrame > 0);
        fm_ipc_store(&shared->command, FM_LINUX_IDLE);
    };
    auto edge = [&](uint32_t kind, uint32_t key) {
        assert(pump(&window, event));
        assert(errno == EDOM && event.type == kind && event.key == key && event.time == 1.25 && event.marker == 731);
    };
    request(FM_LINUX_KEY);
    assert(shared->command == FM_LINUX_RUNNING && shared->actionOwned);
    assert(fm_linux_cleanup() == -EBUSY);
    assert(!wrongCaller(&window, event) && shared->command == FM_LINUX_RUNNING);
    std::thread worker([&] { assert(!pump(&window, event)); });
    worker.join();
    assert(shared->command == FM_LINUX_RUNNING);
    window.real = true;
    assert(pump(&window, event) && event.type == 55 && event.key == 123 && shared->command == FM_LINUX_RUNNING);
    window.real = false;
    edge(19, 11);
    edge(19, 17);
    edge(39, 17);
    edge(39, 11);
    assert(shared->command == FM_LINUX_RUNNING && shared->actionOwned);
    assert(!pump(&window, event));
    consume(0);
    assert(!shared->actionOwned);
    request(FM_LINUX_KEY);
    edge(19, 11);
    shared->cancel = 1;
    edge(39, 11);
    assert(!pump(&window, event));
    consume(ECANCELED);
    assert(!shared->actionOwned);
    for (uint32_t cleanup : {FM_LINUX_CLEANUP, FM_LINUX_DETACH}) {
        request(FM_LINUX_KEY);
        edge(19, 11);
        failClock = true;
        assert(!pump(&window, event));
        consume(EIO);
        assert(shared->actionOwned && shared->attached);
        request(cleanup);
        assert(shared->command == FM_LINUX_RUNNING && shared->actionOwned);
        failClock = false;
        edge(39, 11);
        assert(!pump(&window, event));
        assert(shared->command == FM_LINUX_RUNNING && shared->actionOwned);
        invoke(&gui);
        consume(EIO);
        assert(!shared->actionOwned && shared->attached);
        request(FM_LINUX_FRAME);
        consume(0);
    }
    request(FM_LINUX_DETACH);
    consume(0);
    assert(!shared->attached && !shared->pointerOwned && !shared->protectionOwned && !shared->actionOwned);
    assert(!pump(&window, event));
    assert(fm_linux_cleanup() == 0);
    assert(fm_linux_attach() == 0);
    request(FM_LINUX_KEY);
    edge(19, 11);
    shared->cancel = 1;
    edge(39, 11);
    assert(!pump(&window, event));
    consume(ECANCELED);
    request(FM_LINUX_DETACH);
    consume(0);
}
