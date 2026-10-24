@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.KeyboardModifierLayout
import kotlinx.cinterop.set

/** SDL event modifiers are copied by Event::convertSDLEvent and InputState::update. */
internal class KeyboardModifierLayouts(types: DebugTypes) {
    private val event = types.namedMember("Event", "keyboard", "KeyboardEvent", types.aggregateSize("KeyboardEvent")) +
        types.scalarMember("KeyboardEvent", "mod", 2u, 7u)
    private val state = types.scalarMember("InputState", "modState", 2u, 7u)
    private val keys = listOf(
        "LSHIFT", "RSHIFT", "LCTRL", "RCTRL", "LALT", "RALT", "LGUI", "RGUI", "MODE",
        "NUMLOCKCLEAR", "CAPSLOCK", "SCROLLLOCK",
    )
        .map { types.enumValue("SDL_Scancode", "SDL_SCANCODE_$it") }

    init {
        types.scalarMember("SDL_KeyboardEvent", "mod", 2u, 7u)
        types.namedMember("SDL_KeyboardEvent", "scancode", "SDL_Scancode", 4u)
    }

    fun write(target: KeyboardModifierLayout) {
        target.event = event
        target.state = state
        keys.forEachIndexed { index, value -> target.keys[index] = value }
    }
}
