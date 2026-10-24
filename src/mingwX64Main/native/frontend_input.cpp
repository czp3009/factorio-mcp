#include "frontend_input.h"
#include "protocol.h"
#include "keyboard_modifiers.h"
#include <cstring>

void writeKeyboardEvent(const Symbols &symbols, void *optional, uint32_t key, bool down) {
    const auto &layout = symbols.input;
    auto *storage = static_cast<unsigned char *>(optional);
    require(layout.supported && !storage[layout.optionalEngaged], "Expected an empty event optional");
    auto *event = storage + layout.optionalValue;
    const auto *global = *reinterpret_cast<unsigned char **>(symbols.address[Global]);
    require(global, "Global input owner is unavailable");
    void *state;
    memcpy(&state, global + layout.globalInputState, sizeof(state));
    require(state, "Global input state is unavailable");
    const auto nanoseconds = reinterpret_cast<uint64_t (*)()>(symbols.address[SdlTicksNS])();
    // Verified Win64 constructor: this in RCX, timestamp in XMM1, Event::Type in R8D.
    reinterpret_cast<void *(*)(void *, double, int)>(symbols.address[EventConstructor])(
        event, nanoseconds / 1e9, down ? layout.keyDown : layout.keyUp);
    memcpy(event + layout.keyboard + layout.scancode, &key, sizeof(key));
    writeKeyboardModifiers(layout.modifiers, state, event, key, down);
    storage[layout.optionalEngaged] = 1;
}
