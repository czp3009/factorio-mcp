#include "../../nativeMain/native/rendering_state.h"
#include "../../nativeMain/native/rendering_control.h"
#include <cassert>
#include <cstddef>
#include <cstring>
#include <initializer_list>

struct Settings {
    uint64_t table = 42;
    char padding[23]{};
    uint8_t halt = 0;
};

struct Global {
    char padding[41]{};
    uint8_t minimized = 0;
    uint8_t suppressed = 0;
    Settings *settings = nullptr;
};

int main() {
    Settings settings;
    Global global;
    auto *pointer = &global;
    uint8_t headless = 0;
    FmRenderingState layout{reinterpret_cast<uintptr_t>(&pointer), reinterpret_cast<uintptr_t>(&headless), 42,
        sizeof(Global), sizeof(Settings), offsetof(Global, minimized), offsetof(Global, suppressed),
        offsetof(Global, settings), offsetof(Settings, halt)};
    auto read = [](uint64_t address, auto &output) {
        memcpy(&output, reinterpret_cast<const void *>(address), sizeof(output));
        return true;
    };
    for (int flags = 0; flags < 32; ++flags) {
        headless = (flags & 1) != 0;
        global.suppressed = (flags & 2) != 0;
        global.minimized = (flags & 4) != 0;
        global.settings = (flags & 8) ? &settings : nullptr;
        settings.halt = (flags & 16) != 0;
        const bool suppressed = headless || global.suppressed || (global.minimized && global.settings && settings.halt);
        assert(renderingAdmission(layout, read) == (suppressed ? FM_RENDERING_SUSPENDED : FM_RENDERING_AVAILABLE));
    }
    headless = global.suppressed = 0;
    global.minimized = 1;
    global.settings = &settings;
    settings.table = 43;
    assert(renderingAdmission(layout, read) == FM_RENDERING_UNREADABLE);
    settings.table = 42;
    settings.halt = 2;
    assert(renderingAdmission(layout, read) == FM_RENDERING_UNREADABLE);
    settings.halt = 0;
    assert(renderingAdmission(layout, [](uint64_t, auto &) { return false; }) == FM_RENDERING_UNREADABLE);
    layout.halt = sizeof(Settings);
    assert(renderingAdmission(layout, read) == FM_RENDERING_INVALID);
    layout.halt = offsetof(Settings, halt);
    layout.settings = sizeof(Global) - 4;
    assert(renderingAdmission(layout, read) == FM_RENDERING_INVALID);

    layout.settings = offsetof(Global, settings);
    FmRenderingControl control{layout.global, layout.headless, layout.globalSize, layout.suppressed};
    unsigned writes = 0;
    auto write = [&](uint64_t address, uint8_t value) {
        assert(address == reinterpret_cast<uintptr_t>(&global.suppressed) && value <= 1);
        ++writes;
        global.suppressed = value;
        return true;
    };
    global.minimized = 0;
    for (uint8_t halt : {uint8_t{0}, uint8_t{1}}) {
        control.enabled = 0;
        settings.halt = halt;
        global.suppressed = 0;
        const auto before = writes;
        assert(setRendering(control, read, write) == FM_RENDERING_APPLIED);
        assert(setRendering(control, read, write) == FM_RENDERING_APPLIED);
        assert(writes == before + 1 && global.suppressed == 1 && global.minimized == 0 && settings.halt == halt);
        assert(renderingAdmission(layout, read) == FM_RENDERING_SUSPENDED);
        control.enabled = 1;
        assert(setRendering(control, read, write) == FM_RENDERING_APPLIED);
        assert(setRendering(control, read, write) == FM_RENDERING_APPLIED);
        assert(writes == before + 2 && !global.suppressed && !global.minimized && settings.halt == halt);
        assert(renderingAdmission(layout, read) == FM_RENDERING_AVAILABLE);
        global.minimized = 1;
        assert(setRendering(control, read, write) == FM_RENDERING_APPLIED);
        assert(global.minimized && settings.halt == halt && writes == before + 2);
        assert(renderingAdmission(layout, read) == (halt ? FM_RENDERING_SUSPENDED : FM_RENDERING_AVAILABLE));
        global.minimized = 0;
    }
    control.enabled = 0;
    const auto before = writes;
    auto invalid = control;
    invalid.suppressed = sizeof(Global);
    assert(setRendering(invalid, read, write) == FM_RENDERING_CONTROL_INVALID);
    invalid = control;
    invalid.enabled = 2;
    assert(setRendering(invalid, read, write) == FM_RENDERING_CONTROL_INVALID);
    invalid = control;
    invalid.globalSize = 0;
    assert(setRendering(invalid, read, write) == FM_RENDERING_CONTROL_INVALID);
    headless = 1;
    assert(setRendering(control, read, write) == FM_RENDERING_CONTROL_HEADLESS);
    headless = 0;
    global.suppressed = 2;
    assert(setRendering(control, read, write) == FM_RENDERING_CONTROL_UNREADABLE);
    global.suppressed = 0;
    assert(setRendering(control, [](uint64_t, auto &) { return false; }, write) == FM_RENDERING_CONTROL_UNREADABLE);
    assert(setRendering(control, read, [](uint64_t, uint8_t) { return false; }) == FM_RENDERING_CONTROL_UNWRITABLE);
    assert(writes == before && global.suppressed == 0);
    pointer = nullptr;
    assert(setRendering(control, read, write) == FM_RENDERING_CONTROL_UNREADABLE);
}
