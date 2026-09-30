#include "frontend_input.h"
#include "protocol.h"
#include <cstring>

void writeKeyboardEvent(const Symbols &symbols, void *optional, uint32_t key, bool down) {
    const auto &layout = symbols.input;
    auto *storage = static_cast<unsigned char *>(optional);
    require(layout.supported && !storage[layout.optionalEngaged], "Expected an empty event optional");
    auto *event = storage + layout.optionalValue;
    const auto ticks = reinterpret_cast<uint32_t (*)()>(symbols.address[SdlTicks])();
    // Verified Win64 constructor: this in RCX, timestamp in XMM1, Event::Type in R8D.
    reinterpret_cast<void *(*)(void *, double, int)>(symbols.address[EventConstructor])(
        event, ticks / 1000.0, down ? layout.keyDown : layout.keyUp);
    memcpy(event + layout.keyboard + layout.scancode, &key, sizeof(key));
    storage[layout.optionalEngaged] = 1;
}
