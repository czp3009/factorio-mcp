@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.SpriteLayout
import kotlinx.cinterop.set

/** Reads icon references and source sprite data without selecting a rendered state. */
internal class SpriteLayouts(types: DebugTypes, descriptors: List<ULong>) {
    private val type = descriptors.single()

    private fun icon(types: DebugTypes, member: String) =
        types.pointerPath("IconButton", "icon", member, target = "Sprite", indirections = 1)

    private val normal = icon(types, "sprite")
    private val hovered = icon(types, "hoveredSprite")
    private val disabled = icon(types, "disabledSprite")
    private val filename =
        types.pointerPath(
            "Sprite",
            "fileName",
            "_Ptr",
            target = "std::basic_string<char,std::char_traits<char>,std::allocator<char> >",
            indirections = 1,
        )
    private val x = types.scalarMember("Sprite", "x", 2uL, 6u)
    private val y = types.scalarMember("Sprite", "y", 2uL, 6u)
    private val width = types.scalarMember("Sprite", "width", 2uL, 6u)
    private val height = types.scalarMember("Sprite", "height", 2uL, 6u)
    private val scale = types.scalarMember("Sprite", "scale", 8uL, 8u)
    private val shift =
        types.namedMember("Sprite", "shift", "Vector", types.aggregateSize("Vector"))
    private val shiftX = shift + types.scalarMember("Vector", "x", 8uL, 8u)
    private val shiftY = shift + types.scalarMember("Vector", "y", 8uL, 8u)
    private val tint = types.namedMember("Sprite", "tint", "Color", types.aggregateSize("Color"))
    private val channels =
        listOf("r", "g", "b", "a").map { tint + types.scalarMember("Color", it, 4uL, 8u) }
    private val next =
        types.pointerPath("Sprite", "next", "value", target = "Sprite", indirections = 1)
    private val extra =
        types.pointerPath("Sprite", "extra", "value", target = "Sprite", indirections = 1)
    private val empty = types.byteMember("Sprite", "intentionallyEmpty", true)

    fun write(target: SpriteLayout) {
        target.type = type
        target.normal = normal
        target.hovered = hovered
        target.disabled = disabled
        target.filename = filename
        target.x = x
        target.y = y
        target.width = width
        target.height = height
        target.scale = scale
        target.shiftX = shiftX
        target.shiftY = shiftY
        channels.forEachIndexed { index, offset -> target.tint[index] = offset }
        target.next = next
        target.extra = extra
        target.empty = empty
        target.supported = 1u
    }
}
