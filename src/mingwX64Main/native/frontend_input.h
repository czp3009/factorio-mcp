#pragma once
#include "bridge.h"
#include "../../nativeMain/native/key_gesture.h"

// Called only for an empty, game-constructed optional<Event> at the normal event pump.
void writeKeyboardEvent(const Symbols &symbols, void *optional, uint32_t key, bool down);
