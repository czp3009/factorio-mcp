@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.IconLayout as NativeIconLayout

/**
 * Read the widget's own references without inspecting image storage or selecting a rendered state.
 */
internal class IconLayout(types: DebugTypes, descriptors: List<ULong>) {
    private val type = descriptors.single()

    private fun icon(types: DebugTypes, member: String) =
        types.pointerPath("IconButton", "icon", member, target = "Sprite", indirections = 1)

    private val normal = icon(types, "sprite")
    private val hovered = icon(types, "hoveredSprite")
    private val disabled = icon(types, "disabledSprite")

    fun write(target: NativeIconLayout) {
        target.type = type
        target.normal = normal
        target.hovered = hovered
        target.disabled = disabled
        target.supported = 1u
    }
}
