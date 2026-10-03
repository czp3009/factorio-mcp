#include "pump_input.h"
#include <algorithm>
#include <cerrno>
#include <stdexcept>
#include <system_error>

namespace {
void check(int error, const char *operation) {
    if (error)
        throw std::system_error(error, std::generic_category(), operation);
}
} // namespace

PumpInputEmitter::PumpInputEmitter(EventPump &pump, const KeyboardPumpConfig &keyboard,
                                   const PointerPumpConfig &pointer, EventPump::Pump entry, void *context,
                                   PointerPump::Guard guard, void *guardContext, pid_t evaluationThread)
    : pump_(pump), keyboard_(keyboard), pointer_(pointer), entry_(entry), context_(context),
      evaluationThread_(evaluationThread),
      motion_(pump, pointer, entry, context, guard, guardContext, evaluationThread) {}

void PumpInputEmitter::move(InputPosition position) {
    PointerMoveProgress progress;
    check(motion_.move(position, progress), "factorio-mcp: pointer motion");
}

void PumpInputEmitter::wheel(int32_t direction) {
    EventPumpProgress progress;
    check(motion_.wheel(direction, progress), "factorio-mcp: pointer wheel");
}

void PumpInputEmitter::button(InputButton button, bool down) {
    auto found = std::find_if(slots_.begin(), slots_.end(),
                              [&](const Slot &slot) { return (slot.key || slot.mouse) && slot.button == button; });
    if (!down) {
        // InputSequence records the requested down before calling us. A validation failure before creating
        // a slot has no native obligation, so its matching sequence cleanup is already complete.
        if (found == slots_.end())
            return;
        const int error = found->key ? found->key->release() : found->mouse->release();
        const bool retained = found->key ? found->key->owned() : found->mouse->owned();
        if (retained)
            check(error ? error : EPROTO, "factorio-mcp: input release remains owned");
        // Historical dispatch failure remains in InputSequence's original reason. A completed cleanup
        // returns normally so the sequence can pop this obligation; it must not retry a delivered up.
        found->key.reset();
        found->mouse.reset();
        found->button = {};
        return;
    }
    if (found != slots_.end())
        throw std::logic_error("factorio-mcp: input button already admitted");
    if (button.device != InputDevice::Keyboard && button.device != InputDevice::Mouse)
        throw std::invalid_argument("factorio-mcp: invalid input device");
    auto free = std::find_if(slots_.begin(), slots_.end(), [](const Slot &slot) { return !slot.key && !slot.mouse; });
    if (free == slots_.end())
        throw std::length_error("factorio-mcp: input chord exceeds eight buttons");
    free->button = button;
    int error;
    if (button.device == InputDevice::Keyboard) {
        free->key.emplace(pump_, keyboard_, entry_, context_, evaluationThread_);
        error = free->key->press(button.code);
    } else {
        free->mouse.emplace(pump_, pointer_, entry_, context_, evaluationThread_);
        error = free->mouse->press(button.code);
    }
    check(error, "factorio-mcp: input press");
}

bool PumpInputEmitter::owned() const {
    return std::any_of(slots_.begin(), slots_.end(), [](const Slot &slot) {
        return slot.key ? slot.key->owned() : slot.mouse && slot.mouse->owned();
    });
}
