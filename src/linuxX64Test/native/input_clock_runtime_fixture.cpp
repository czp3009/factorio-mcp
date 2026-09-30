#include "input_clock.h"
#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <initializer_list>
#include <limits>
#include <sys/mman.h>
#include <unistd.h>

static void require(bool condition, const char *message) {
    if (!condition) {
        std::fprintf(stderr, "factorio-mcp input clock fixture: %s\n", message);
        std::abort();
    }
}

struct Handler {
    const uintptr_t *table;
    unsigned unused = 0;
    double time = 0;
};

struct Gui {
    unsigned char padding[37]{};
    Handler *input = nullptr;
};

static Gui *current;
static Handler *replacement;
static unsigned calls = 0;
static unsigned mode = 0;

static double clockTime(const void *pointer) {
    require(pointer == current->input, "clock received the wrong native receiver");
    ++calls;
    const auto result = static_cast<const Handler *>(pointer)->time;
    if (mode == 1 || mode == 3)
        current->input = replacement;
    if (mode == 2 || mode == 3)
        throw 1;
    return result;
}

int main() {
    const uintptr_t table[] = {0, 0, reinterpret_cast<uintptr_t>(&clockTime)};
    const uintptr_t wrongTable[] = {0, 0, 1};
    Handler input{table, 0, 123.5};
    Handler other{table, 0, 999.25};
    Gui gui;
    gui.input = &input;
    current = &gui;
    replacement = &other;
    const FmLinuxInputClockLayout layout{sizeof(Gui), offsetof(Gui, input), sizeof(Handler), 2,
        reinterpret_cast<uintptr_t>(table), reinterpret_cast<uintptr_t>(&clockTime)};
    double result = -99;
    require(validInputClockLayout(layout), "fixture metadata is invalid");
    require(readInputClock(&gui, layout, result) == 0 && result == 123.5 && calls == 1,
            "clock lost its receiver or double result");
    input.time = 14.125;
    require(readInputClock(&gui, layout, result) == 0 && result == 14.125 && calls == 2,
            "clock value was cached or inferred");
    for (double invalid : {-1.0, std::numeric_limits<double>::infinity(), std::numeric_limits<double>::quiet_NaN()}) {
        input.time = invalid;
        require(readInputClock(&gui, layout, result) == ERANGE && result == 0,
                "invalid native timestamp was exposed");
    }
    input.time = 123.5;
    for (unsigned selected = 1; selected <= 3; ++selected) {
        mode = selected;
        const auto before = calls;
        require(readInputClock(&gui, layout, result) == (selected == 1 ? ESTALE : EIO) && result == 0 && calls == before + 1,
                "replacement or exception was retried or lost");
        gui.input = &input;
    }
    mode = 0;
    const auto before = calls;
    gui.input = nullptr;
    require(readInputClock(&gui, layout, result) == ENOENT && result == 0 && calls == before,
            "missing input used a fallback clock");
    gui.input = &input;
    input.table = wrongTable;
    require(readInputClock(&gui, layout, result) == EPROTO && calls == before,
            "unverified table reached a clock call");
    auto wrong = layout;
    wrong.handlerTable = reinterpret_cast<uintptr_t>(wrongTable);
    require(readInputClock(&gui, wrong, result) == EPROTO && calls == before,
            "unverified slot target reached a clock call");
    input.table = table;
    for (unsigned invalid = 0; invalid < 6; ++invalid) {
        wrong = layout;
        if (invalid == 0) wrong.guiSize = wrong.member;
        if (invalid == 1) wrong.member += 1;
        if (invalid == 2) wrong.handlerSize = 1;
        if (invalid == 3) wrong.slot = 4096;
        if (invalid == 4) wrong.handlerTable = UINTPTR_MAX - 7;
        if (invalid == 5) wrong.function = 0;
        require(readInputClock(&gui, wrong, result) == EINVAL && result == 0 && calls == before,
                "invalid layout reached a clock call");
    }
    const auto page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    void *guard = mmap(nullptr, page, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    require(guard != MAP_FAILED, "cannot allocate guard page");
    require(readInputClock(guard, layout, result) == EFAULT && result == 0, "unreadable GUI was dereferenced");
    gui.input = static_cast<Handler *>(guard);
    require(readInputClock(&gui, layout, result) == EFAULT && result == 0, "unreadable handler was dereferenced");
    gui.input = &input;
    input.table = static_cast<const uintptr_t *>(guard);
    wrong = layout;
    wrong.handlerTable = reinterpret_cast<uintptr_t>(guard);
    require(readInputClock(&gui, wrong, result) == EFAULT && result == 0 && calls == before,
            "unreadable dispatch table was called");
    require(munmap(guard, page) == 0, "cannot release guard page");
    input.table = table;
    require(readInputClock(&gui, layout, result) == 0 && result == input.time && calls == before + 1,
            "clock did not recover after rejected admission");
    std::puts("factorio-mcp input clock fixture: passed");
}
