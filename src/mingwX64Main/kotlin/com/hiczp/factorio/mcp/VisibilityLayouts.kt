@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.VisibilityLayout

/** Shared widget flags, including their bit values, come from the selected game's PDB. */
internal class VisibilityLayouts(types: DebugTypes) {
    private val flags = types.scalarMember("agui::Widget", "usageBitMask", 4uL, 7u)
    private val visible = types.enumValue("agui::Widget", "VISIBLE")
    private val render = types.enumValue("agui::Widget", "RENDER")
    private val hiddenBySearch = types.enumValue("agui::Widget", "HIDDEN_BY_SEARCH")

    init {
        val masks = listOf(visible, render, hiddenBySearch)
        check(
            masks.distinct().size == masks.size && masks.all { it != 0u && it and (it - 1u) == 0u }
        ) {
            "Unsupported widget visibility flags"
        }
    }

    fun write(target: VisibilityLayout) {
        target.flags = flags
        target.visible = visible
        target.render = render
        target.hiddenBySearch = hiddenBySearch
        target.supported = 1u
    }
}
