@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.FmRenderingState
import com.hiczp.factorio.mcp.nativebridge.FmRenderingControl

internal class RenderingControlLayouts(types: DebugTypes, val global: ULong, val headless: ULong) {
    val globalSize = types.aggregateSize("GlobalContext").also { require(it in 8uL..67108864uL) }
    val suppressed = types.byteMember("GlobalContext", "displayOccluded", true)

    fun write(output: FmRenderingControl) {
        output.global = global
        output.headless = headless
        output.globalSize = globalSize.toUInt()
        output.suppressed = suppressed
    }
}

/** Fields of the native presentation guard, with full PDB type and extent validation. */
internal class RenderingLayouts(types: DebugTypes, private val control: RenderingControlLayouts) {
    private val settingsSize = types.aggregateSize("GraphicsSettings").also { require(it in 8uL..67108864uL) }
    private val minimized = types.byteMember("GlobalContext", "displayMinimized", true)
    private val settings = types.pointerPath(
        "GlobalContext", "graphicsSettings", "value", target = "GraphicsSettings", indirections = 1,
    )
    private val halt = types.scalarPath(
        "GraphicsSettings", "haltRenderingWhenMinimized", "value", target = 10u, bytes = 1uL,
    )

    fun write(output: FmRenderingState) {
        output.global = control.global
        output.headless = control.headless
        output.globalSize = control.globalSize.toUInt()
        output.settingsSize = settingsSize.toUInt()
        output.minimized = minimized
        output.suppressed = control.suppressed
        output.settings = settings
        output.halt = halt
    }
}
