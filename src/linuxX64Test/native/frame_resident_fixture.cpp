#include "resident.h"
#include "linux_ipc.h"
#include <cassert>
#include <cerrno>
#include <cstddef>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>

class Gui {
public:
    uintptr_t caller = 0;
    virtual void logic() { caller = reinterpret_cast<uintptr_t>(__builtin_return_address(0)); }
};

__attribute__((noinline)) static void logic(Gui *gui) {
    asm volatile("" : "+r"(gui) : : "memory");
    gui->logic();
    asm volatile("" : : : "memory");
}

struct Device { void (*swap)(void *, void *); };
struct Window;
struct Graphics { uintptr_t *table; Window *window; };
struct Window { uintptr_t *table; Graphics *graphics; void *native; };
struct Global { Window *window; };
static uintptr_t swapCaller;
static unsigned swaps = 0, reads = 0;

__attribute__((noinline)) static void originalSwap(void *, void *) {
    swapCaller = reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    ++swaps;
}

__attribute__((noinline)) static void swap(Device *device, void *window) {
    asm volatile("" : "+r"(device) : : "memory");
    device->swap(device, window);
    asm volatile("" : : : "memory");
}

static uint64_t dimensions(void *) { return uint64_t{2} << 32 | 2; }
static void getInteger(uint32_t field, int32_t *value) { *value = field == 0x0c02 ? 0x0405 : 0; }
static void bind(uint32_t, uint32_t) {}
static void pixelStore(uint32_t, int32_t) {}
static uint32_t error() { return 0; }
static void pixels(int32_t, int32_t, int32_t width, int32_t height, uint32_t, uint32_t, void *output) {
    assert(width == 2 && height == 2);
    ++reads;
    std::memset(output, 1, 6);
    std::memset(static_cast<unsigned char *>(output) + 6, 2, 6);
}

int main() {
    alarm(30);
    assert(fm_linux_initialize() >= 0);
    auto *shared = reinterpret_cast<FmLinuxShared *>(fm_linux_resident.mapping);
    Gui gui;
    Gui *instance = &gui;
    logic(&gui);
    auto *originalTable = *reinterpret_cast<uintptr_t **>(&gui);
    const size_t pageSize = sysconf(_SC_PAGESIZE);
    auto *page = static_cast<uintptr_t *>(mmap(nullptr, pageSize, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    assert(page != MAP_FAILED);
    std::memcpy(page, originalTable - 2, 3 * sizeof(uintptr_t));
    auto *table = page + 2;
    std::memcpy(&gui, &table, sizeof(table));
    assert(mprotect(page, pageSize, PROT_READ) == 0);
    shared->config = {reinterpret_cast<uintptr_t>(table), *originalTable,
        reinterpret_cast<uintptr_t>(&instance), gui.caller, gui.caller - 1, gui.caller + 1, PROT_READ};

    uintptr_t graphicsTable[1]{}, windowTable[1]{};
    int native = 0;
    Graphics graphics{graphicsTable, nullptr};
    Window window{windowTable, &graphics, &native};
    graphics.window = &window;
    Global global{&window};
    Global *globalPointer = &global;
    Device device{originalSwap};
    Device *devicePointer = &device;
    swap(&device, &native);
    shared->frameContext = {reinterpret_cast<uintptr_t>(&globalPointer), reinterpret_cast<uintptr_t>(&devicePointer),
        swapCaller, reinterpret_cast<uintptr_t>(&dimensions), reinterpret_cast<uintptr_t>(graphicsTable),
        reinterpret_cast<uintptr_t>(windowTable), sizeof(Global), offsetof(Global, window), sizeof(Graphics),
        offsetof(Graphics, window), sizeof(Window), offsetof(Window, graphics), offsetof(Window, native)};
    shared->frameSite = {reinterpret_cast<uintptr_t>(&devicePointer), reinterpret_cast<uintptr_t>(&device),
        reinterpret_cast<uintptr_t>(&originalSwap), sizeof(Device), offsetof(Device, swap), PROT_READ | PROT_WRITE};
    uintptr_t api[]{reinterpret_cast<uintptr_t>(&getInteger), reinterpret_cast<uintptr_t>(&bind),
        reinterpret_cast<uintptr_t>(&bind), reinterpret_cast<uintptr_t>(&pixelStore),
        reinterpret_cast<uintptr_t>(&pixels), reinterpret_cast<uintptr_t>(&error)};
    auto entry = [&](size_t index) { return FmLinuxFrameApiEntry{reinterpret_cast<uintptr_t>(&api[index]), api[index]}; };
    shared->frameApi = {entry(0), entry(1), entry(2), entry(3), entry(4), entry(5)};
    assert(fm_linux_attach() == 0);
    auto request = [&](uint32_t operation) {
        assert(shared->command == FM_LINUX_IDLE);
        shared->operation = operation;
        fm_ipc_store(&shared->cancel, 0);
        fm_ipc_store(&shared->command, FM_LINUX_PENDING);
        logic(&gui);
    };
    auto consume = [&](int result) {
        assert(shared->command == FM_LINUX_COMPLETE && shared->result == -result);
        fm_ipc_store(&shared->command, FM_LINUX_IDLE);
    };
    for (unsigned capture = 1; capture <= 2; ++capture) {
        request(FM_LINUX_SCREENSHOT);
        assert(shared->command == FM_LINUX_RUNNING && device.swap != originalSwap);
        swap(&device, &native);
        consume(0);
        assert(reads == capture && swaps == capture + 1);
        assert(shared->frameSize.width == 2 && shared->frameSize.height == 2 && shared->frameBytes == 12);
        for (unsigned i = 0; i < 12; ++i)
            assert(shared->framePixels[i] == (i < 6 ? 2 : 1));
    }
    request(FM_LINUX_SCREENSHOT);
    fm_ipc_store(&shared->cancel, 1);
    logic(&gui); // No presentation is necessary to acknowledge cancellation.
    consume(ECANCELED);
    assert(shared->frameBytes == 0 && shared->frameSize.width == 0);
    swap(&device, &native);
    assert(reads == 2);
    request(FM_LINUX_SCREENSHOT);
    api[4] = 0; // An entry changes after admission, before rendering.
    swap(&device, &native);
    consume(ESTALE);
    assert(reads == 2 && shared->frameBytes == 0);
    api[4] = reinterpret_cast<uintptr_t>(&pixels);
    request(FM_LINUX_DETACH);
    consume(0);
    assert(!shared->attached && device.swap == originalSwap && *table == *originalTable);
    std::memcpy(&gui, &originalTable, sizeof(originalTable));
    assert(munmap(page, pageSize) == 0);
    return 0;
}
