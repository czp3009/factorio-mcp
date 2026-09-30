#include "frame_context.h"
#include "linux_ipc.h"
#include <cassert>
#include <cerrno>
#include <cstddef>
#include <thread>

namespace {
struct Window { uintptr_t table, graphics, native; };
struct Graphics { uintptr_t table, window; };
struct Global { uintptr_t window; };
uintptr_t windowTable[2]{}, graphicsTable[2]{};
Window window;
Graphics graphics;
Global global;
uintptr_t root, device;
uint32_t cancel;
unsigned calls;
uint64_t dimensions = (uint64_t{720} << 32) | 1280;
bool fail, cancelInside;

uint64_t getter(void *receiver) {
    assert(receiver == reinterpret_cast<void *>(window.graphics));
    ++calls;
    if (fail)
        throw 1;
    if (cancelInside)
        fm_ipc_store(&cancel, 1);
    return dimensions;
}
} // namespace

int main() {
    const auto address = [](auto *value) { return reinterpret_cast<uintptr_t>(value); };
    window = {address(windowTable), address(&graphics), 0x1234};
    graphics = {address(graphicsTable), address(&window)};
    global.window = address(&window);
    root = address(&global);
    device = 0x5678;
    FmLinuxFrameContextConfig config{
        address(&root), address(&device), 0x9000, address(getter),
        address(graphicsTable), address(windowTable), sizeof(global), offsetof(Global, window),
        sizeof(graphics), offsetof(Graphics, window), sizeof(window),
        offsetof(Window, graphics), offsetof(Window, native)
    };
    FmLinuxFrameSize output{};
    auto read = [&](int expected) {
        output = {77, 88};
        assert(readFrameSize(config, device, window.native, config.caller, &cancel, output) == expected);
        if (expected)
            assert(!output.width && !output.height);
    };
    read(0);
    assert(output.width == 1280 && output.height == 720 && calls == 1);
    std::thread foreign([&] { read(EPERM); });
    foreign.join();
    assert(readFrameSize(config, device, window.native, config.caller + 1, &cancel, output) == EPERM);
    assert(readFrameSize(config, device + 1, window.native, config.caller, &cancel, output) == ESTALE);
    assert(readFrameSize(config, device, window.native + 1, config.caller, &cancel, output) == ESTALE);
    window.table = address(graphicsTable);
    read(ESTALE);
    window.table = address(windowTable);
    graphics.window = 0;
    read(ESTALE);
    graphics.window = address(&window);
    graphics.table = address(windowTable);
    read(ESTALE);
    graphics.table = address(graphicsTable);
    root = 8;
    read(EFAULT);
    root = address(&global);
    cancel = 1;
    read(ECANCELED);
    cancel = 0;
    assert(calls == 1);
    auto invalid = config;
    invalid.nativeWindow = invalid.windowGraphics;
    assert(!validFrameContext(invalid));
    invalid = config;
    invalid.graphicsWindow = invalid.graphicsSize;
    assert(!validFrameContext(invalid));
    invalid = config;
    invalid.graphicsWindow = 0;
    assert(!validFrameContext(invalid));
    for (auto value : {uint64_t{0}, (uint64_t{720} << 32) | 8193,
                       (uint64_t{8192} << 32) | 8192, (uint64_t{720} << 32) | 0xffffffff}) {
        dimensions = value;
        read(EOVERFLOW);
    }
    fail = true;
    read(EIO);
    fail = false;
    cancelInside = true;
    read(ECANCELED);
    cancelInside = false;
    cancel = 0;
    dimensions = (uint64_t{1080} << 32) | 1920;
    // A newly selected object is read on the next callback; no previous graphics pointer is retained.
    Graphics replacement{address(graphicsTable), address(&window)};
    window.graphics = address(&replacement);
    read(0);
    assert(output.width == 1920 && output.height == 1080);
}
