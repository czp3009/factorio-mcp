@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.SwitchLayout

/** The switch's own position is not a boolean or a gameplay mode. */
internal class SwitchLayouts(types: DebugTypes, descriptors: List<ULong>) {
    private val type = descriptors.single()
    private val state = types.namedMember("agui::Switch", "state", "agui::SwitchState", 1uL)
    private val allowNone = types.byteMember("agui::Switch", "allowNoneState", true)
    private val states =
        types.enumValues("agui::SwitchState").entries.associate { it.value to it.key }

    fun state(value: Int): String = states[value] ?: "unknown_$value"

    fun write(target: SwitchLayout) {
        target.type = type
        target.state = state
        target.allowNone = allowNone
        target.supported = 1u
    }
}
