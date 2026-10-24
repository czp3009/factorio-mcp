@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.ChatLayout
import kotlinx.cinterop.set

internal class ChatLayouts(types: DebugTypes) {
    private val string = types.memberTypeName("LocalisedString", "key")
    private val stringSize = types.aggregateSize(string).also { check(it in 1uL..256uL) }.toUInt()
    private val console = types.pointerMember("Player", "outputConsole", "OutputConsole")
    private val list = types.memberTypeName("OutputConsole", "items")
    private val node = "std::_List_node<OutputConsole::Item,void *>"
    private val lists =
        listOf("items", "itemsNotPartOfGameState").map {
            types.namedMember("OutputConsole", it, list, types.aggregateSize(list))
        }
    private val head =
        types.pointerPath(list, "_Mypair", "_Myval2", "_Myhead", target = node, indirections = 1)
    private val count =
        types.scalarPath(list, "_Mypair", "_Myval2", "_Mysize", target = 7u, bytes = 8u)
    private val next = types.pointerMember(node, "_Next", node)
    private val previous = types.pointerMember(node, "_Prev", node)
    private val value =
        types.namedMember(
            node,
            "_Myval",
            "OutputConsole::Item",
            types.aggregateSize("OutputConsole::Item"),
        )
    private val tick =
        types.scalarPath("OutputConsole::Item", "updateTick", "value", target = 7u, bytes = 8u)
    private val playerIndex = types.scalarMember("OutputConsole::Item", "playerIndex", 2u, 7u)
    private val playerName =
        types.namedMember("OutputConsole::Item", "playerName", string, stringSize.toULong())
    private val text =
        types.namedMember(
            "OutputConsole::Item",
            "bodyText",
            "LocalisedString",
            types.aggregateSize("LocalisedString"),
        )
    private val cached =
        types.namedMember(
            "LocalisedString",
            "localisation",
            "LocalisationResult",
            types.aggregateSize("LocalisationResult"),
        ) + types.namedMember("LocalisationResult", "result", string, stringSize.toULong())
    private val actionSize = types.size("InputAction")
    private val actionType = types.namedMember("InputAction", "type", "InputActionType", 2u)
    private val actionPlayer = types.scalarMember("InputAction", "playerIndex", 2u, 7u)
    private val actionBuffer = types.byteArrayMember("InputAction", "buffer", stringSize)
    private val writeToConsole =
        types.enumValue("InputActionType", "WriteToConsole").also { check(it <= 65535u) }
    private val gameHandler = types.pointerPath(
        "Game", "inputActionHandler", "value", target = "InputActionHandler", indirections = 1,
    )
    private val handlerInputType = types.namedMember("InputActionHandler", "inputType", "InputType", 4u)
    private val replayInput = types.enumValue("InputType", "Replay")

    init {
        types.plainMembers(
            "InputAction",
            setOf("updateTick", "type", "playerIndex", "align", "buffer"),
        )
        types.namedMember("InputAction", "updateTick", "MapTick", 8u)
        types.scalarMember("MapTick", "value", 8u, 7u)
    }

    fun write(target: ChatLayout) {
        target.supported = 1u
        target.console = console
        lists.forEachIndexed { index, offset -> target.lists[index] = offset }
        target.head = head
        target.count = count
        target.next = next
        target.previous = previous
        target.value = value
        target.tick = tick
        target.playerIndex = playerIndex
        target.playerName = playerName
        target.text = text
        target.cached = cached
        target.stringSize = stringSize
        target.sendSupported = 1u
        target.actionSize = actionSize
        target.actionType = actionType
        target.actionPlayer = actionPlayer
        target.actionBuffer = actionBuffer
        target.writeToConsole = writeToConsole
        target.gameHandler = gameHandler
        target.handlerInputType = handlerInputType
        target.replayInput = replayInput
    }
}
