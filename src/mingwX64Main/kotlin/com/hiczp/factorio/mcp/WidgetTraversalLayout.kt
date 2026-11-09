@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.Symbols
import kotlinx.cinterop.get

/** Typed PDB ranges and the receiver-proven recursive loops establish native sibling order. */
internal class WidgetTraversalLayout(
    types: DebugTypes,
    code: BinaryView,
    address: Long,
    badFunctionCall: Long,
) {
    private val objectSize = types.aggregateSize("agui::Widget").also { require(it in 8uL..UInt.MAX_VALUE.toULong()) }
    private val parent = types.pointerMember("agui::Widget", "parentWidget", "agui::Widget")
    private val vector = "std::vector<agui::Widget *,std::allocator<agui::Widget *> >"
    private val expected = listOf("children", "privateChildren").map { field ->
        val owner = types.namedMember("agui::Widget", field, vector, types.aggregateSize(vector)).toLong()
        fun pointer(name: String) = owner + types.pointerPath(
            vector, "_Mypair", "_Myval2", name, target = "agui::Widget", indirections = 2,
        ).toLong()
        WidgetChildren.Range(pointer("_Myfirst"), pointer("_Mylast"))
    }
    private val ranges = WidgetChildren.analyze(
        code, address, badFunctionCall, objectSize.toLong(), WidgetChildren.Abi.MICROSOFT,
    ).also {
        require(it.size == expected.size && it.toSet() == expected.toSet()) {
            "Recursive UI visitor does not traverse exactly the typed child ranges"
        }
    }

    fun write(target: Symbols) {
        target.widgetSize = objectSize.toUInt()
        target.widgetParentOffset = parent
        ranges.forEachIndexed { index, range ->
            target.widgetChildren[index].begin = range.begin.toUInt()
            target.widgetChildren[index].end = range.end.toUInt()
        }
    }
}
