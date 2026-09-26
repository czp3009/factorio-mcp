@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.TimedInputLayout

/** Metadata for the normal event route and the current client's input evaluation clock. */
internal class TimedInputLayouts(types: DebugTypes) {
    private val eventSize = types.size("Event")
    private val keyboard =
        types.namedMember(
            "Event",
            "keyboard",
            "KeyboardEvent",
            types.aggregateSize("KeyboardEvent"),
        )
    private val scancode =
        keyboard + types.namedMember("KeyboardEvent", "scancode", "SDL_Scancode", 4uL)
    private val mouse =
        types.namedMember("Event", "mouse", "MouseEvent", types.aggregateSize("MouseEvent"))
    private val mouseX = mouse + types.scalarMember("MouseEvent", "x", 4uL, 6u)
    private val mouseY = mouse + types.scalarMember("MouseEvent", "y", 4uL, 6u)
    private val mouseButton = mouse + types.scalarMember("MouseEvent", "button", 4uL, 7u)
    private val mouseWheel = mouse + types.scalarMember("MouseEvent", "dw", 4uL, 6u)
    private val mouseWheelY = mouse + types.scalarMember("MouseEvent", "dy", 4uL, 6u)
    private val stateSize = types.aggregateSize("InputState").also { check(it in 1uL..65536uL) }
    private val stateMouse =
        types.namedMember(
            "InputState",
            "mouseState",
            "InputState::MouseState",
            types.aggregateSize("InputState::MouseState"),
        )
    private val stateMouseX =
        stateMouse + types.scalarMember("InputState::MouseState", "x", 4uL, 6u)
    private val stateMouseY =
        stateMouse + types.scalarMember("InputState::MouseState", "y", 4uL, 6u)
    private val stateMouseInWindow = types.byteMember("InputState", "mouseIsInWindow", true)
    private val events = types.enumValues("Event::Type")
    private val globalInputState =
        types.pointerPath(
            "GlobalContext",
            "inputState",
            "value",
            target = "InputState",
            indirections = 1,
        )
    private val globalSource =
        types.pointerPath(
            "GlobalContext",
            "playerInputSource",
            "value",
            target = "PlayerInputSource",
            indirections = 1,
        )
    private val sourcePlayer = types.pointerMember("PlayerInputSource", "player", "Player")
    private val playerMap = types.pointerMember("Player", "map", "Map")
    private val mapTick =
        types.namedMember("Map", "updateTick", "MapTick", types.aggregateSize("MapTick")) +
                types.scalarMember("MapTick", "value", 8uL, 7u)

    init {
        types.plainMembers("MouseEvent", setOf("x", "y", "dx", "dy", "dw", "button", "isTouch"))
        listOf("dx", "dy", "dw").forEach { types.scalarMember("MouseEvent", it, 4uL, 6u) }
        types.byteMember("MouseEvent", "isTouch", true)
        listOf(
            "KEY_DOWN",
            "KEY_UP",
            "MOUSE_MOVE",
            "MOUSE_ENTER",
            "MOUSE_BUTTON_DOWN",
            "MOUSE_BUTTON_UP",
            "MOUSE_WHEEL",
        )
            .forEach { events.getValue(it) }
    }

    fun write(target: TimedInputLayout) {
        target.supported = 1u
        target.events.eventSize = eventSize
        target.events.scancode = scancode
        target.events.mouseX = mouseX
        target.events.mouseY = mouseY
        target.events.mouseButton = mouseButton
        target.events.mouseWheel = mouseWheel
        target.events.mouseWheelY = mouseWheelY
        target.events.wheel = events.getValue("MOUSE_WHEEL")
        target.events.stateSize = stateSize.toUInt()
        target.events.stateMouseX = stateMouseX
        target.events.stateMouseY = stateMouseY
        target.events.stateMouseInWindow = stateMouseInWindow
        target.events.mouseEnter = events.getValue("MOUSE_ENTER")
        target.events.keyDown = events.getValue("KEY_DOWN")
        target.events.keyUp = events.getValue("KEY_UP")
        target.events.mouseMove = events.getValue("MOUSE_MOVE")
        target.events.mouseDown = events.getValue("MOUSE_BUTTON_DOWN")
        target.events.mouseUp = events.getValue("MOUSE_BUTTON_UP")
        target.globalInputState = globalInputState
        target.globalSource = globalSource
        target.sourcePlayer = sourcePlayer
        target.playerMap = playerMap
        target.mapTick = mapTick
    }
}
