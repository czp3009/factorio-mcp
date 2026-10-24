#pragma once
#include "bridge.h"
#include <cstring>

inline void writeKeyboardModifiers(const KeyboardModifierLayout &layout, const void *state, void *event,
                                   uint32_t key, bool down) {
    // SDL3 SDL_Keymod ABI constants, in the same order as the PDB-resolved modifier scancodes.
    // https://github.com/libsdl-org/SDL/blob/main/include/SDL3/SDL_keycode.h
    constexpr uint16_t masks[]{0x0001, 0x0002, 0x0040, 0x0080, 0x0100, 0x0200, 0x0400, 0x0800,
                               0x4000, 0x1000, 0x2000, 0x8000};
    uint16_t modifiers;
    memcpy(&modifiers, static_cast<const unsigned char *>(state) + layout.state, sizeof(modifiers));
    for (unsigned i = 0; i < 12; ++i)
        if (key == layout.keys[i]) {
            if (i < 9)
                modifiers = down ? modifiers | masks[i] : modifiers & ~masks[i];
            else if (down)
                modifiers ^= masks[i];
        }
    memcpy(static_cast<unsigned char *>(event) + layout.event, &modifiers, sizeof(modifiers));
}
