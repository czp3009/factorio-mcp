@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.ProgressLayout

/** Read the progress widget's own values without assigning a gameplay meaning or range. */
internal class ProgressLayouts(types: DebugTypes, descriptors: List<ULong>) {
    private val type = descriptors.single()
    private val value = types.scalarMember("agui::ProgressBar", "value", 8uL, 8u)
    private val direction =
        types.namedMember("agui::ProgressBar", "direction", "agui::GuiDirection", 1uL) +
                types.namedMember("agui::GuiDirection", "value", "agui::GuiDirection::Enum", 1uL)
    private val hasText = types.byteMember("agui::ProgressBar", "hasText", true)
    private val directions =
        types.enumValues("agui::GuiDirection::Enum").entries.associate { it.value to it.key }

    fun direction(value: Int): String = directions[value] ?: "unknown_$value"

    fun write(target: ProgressLayout) {
        target.type = type
        target.value = value
        target.direction = direction
        target.hasText = hasText
        target.supported = 1u
    }
}
