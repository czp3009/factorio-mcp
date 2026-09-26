@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.FrontendInputLayout

internal class FrontendInputLayouts(types: DebugTypes) {
    private val eventSize = types.size("Event")
    private val optionalValue =
        types.namedMember("std::optional<Event>", "_Value", "Event", eventSize.toULong())
    private val optionalEngaged = types.byteMember("std::optional<Event>", "_Has_value", true)
    private val keyboard =
        types.namedMember(
            "Event",
            "keyboard",
            "KeyboardEvent",
            types.aggregateSize("KeyboardEvent"),
        )
    private val scancode = types.namedMember("KeyboardEvent", "scancode", "SDL_Scancode", 4uL)
    private val keyDown = types.enumValue("Event::Type", "KEY_DOWN")
    private val keyUp = types.enumValue("Event::Type", "KEY_UP")
    private val keys =
        types
            .enumValues("SDL_Scancode")
            .filterKeys { it.startsWith("SDL_SCANCODE_") && it != "SDL_SCANCODE_UNKNOWN" }
            .mapKeys { it.key.removePrefix("SDL_SCANCODE_") }

    init {
        types.plainMembers("KeyboardEvent", setOf("scancode", "unichar", "repeat"))
        types.scalarMember("KeyboardEvent", "unichar", 4uL, 6u)
        types.byteMember("KeyboardEvent", "repeat", true)
        check(optionalEngaged >= optionalValue + eventSize)
    }

    fun write(target: FrontendInputLayout) {
        target.supported = 1u
        target.optionalValue = optionalValue
        target.optionalEngaged = optionalEngaged
        target.eventSize = eventSize
        target.keyboard = keyboard
        target.scancode = scancode
        target.keyDown = keyDown
        target.keyUp = keyUp
    }

    fun keys(names: List<String>): List<UInt> =
        names
            .map { (keys[it] ?: error("Unknown key '$it'; use input_bindings key names")).toUInt() }
            .also { require(it.distinct().size == it.size) { "Key chord contains duplicate keys" } }
}
