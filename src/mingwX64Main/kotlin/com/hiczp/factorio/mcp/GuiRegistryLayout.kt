@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.FmGuiRegistryLayout

/** Only the existing widget/index association needs native metadata; attributes use the Lua API. */
internal class GuiRegistryLayout(types: DebugTypes) {
    private val playerGui = types.pointerMember("Player", "customGui", "CustomGui")
    private val mapType = types.memberTypeName("CustomGui", "elementByWidget").also {
        require(it.startsWith("std::map<agui::Widget *,CustomGuiElement *,"))
    }
    private val map = types.namedMember("CustomGui", "elementByWidget", mapType, types.aggregateSize(mapType))
    private var valueType = mapType
    private var valueOffset = 0u
    private val head: UInt
    private val count: UInt
    private val nodeType: String
    private val key: UInt
    private val value: UInt
    private val playerSize = types.aggregateSize("Player").toUInt()
    private val guiSize = types.aggregateSize("CustomGui").toUInt()
    private val elementSize = types.aggregateSize("CustomGuiElement").toUInt()
    private val elementWidget = types.pointerMember("CustomGuiElement", "widget", "agui::Widget")
    private val elementGui = types.pointerMember("CustomGuiElement", "parentGui", "CustomGui")
    private val elementIndex = types.scalarMember("CustomGuiElement", "index", 4uL, 7u)
    private val nodeSize: UInt
    private val left: UInt
    private val right: UInt
    private val parent: UInt
    private val nil: UInt

    init {
        val visited = mutableSetOf<String>()
        while (runCatching { types.member(valueType, "_Myhead") }.isFailure) {
            require(visited.add(valueType)) { "Cyclic map metadata" }
            val field = if (runCatching { types.member(valueType, "_Mypair") }.isSuccess) "_Mypair" else "_Myval2"
            valueOffset += types.member(valueType, field).offset
            valueType = types.memberTypeName(valueType, field)
        }
        nodeType = types.pointerTargetName(valueType, "_Myhead").also { require(it.startsWith("std::_Tree_node<")) }
        nodeSize = types.aggregateSize(nodeType).toUInt()
        left = types.pointerMember(nodeType, "_Left", nodeType)
        right = types.pointerMember(nodeType, "_Right", nodeType)
        parent = types.pointerMember(nodeType, "_Parent", nodeType)
        nil = types.scalarMember(nodeType, "_Isnil", 1uL, 2u)
        head = valueOffset + types.pointerMember(valueType, "_Myhead", nodeType)
        count = valueOffset + types.scalarMember(valueType, "_Mysize", 8uL, 7u)
        val pairType = types.memberTypeName(nodeType, "_Myval")
        val pair = types.namedMember(nodeType, "_Myval", pairType, types.aggregateSize(pairType))
        key = pair + types.pointerMember(pairType, "first", "agui::Widget")
        value = pair + types.pointerMember(pairType, "second", "CustomGuiElement")
    }

    fun write(target: FmGuiRegistryLayout) {
        target.supported = 1u
        target.playerSize = playerSize
        target.playerGui = playerGui
        target.guiSize = guiSize
        target.map = map
        target.mapHead = head
        target.mapCount = count
        target.nodeSize = nodeSize
        target.left = left
        target.right = right
        target.parent = parent
        target.nil = nil
        target.key = key
        target.value = value
        target.elementSize = elementSize
        target.elementWidget = elementWidget
        target.elementGui = elementGui
        target.elementIndex = elementIndex
        target.embeddedHead = 0u
    }
}
