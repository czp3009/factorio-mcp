@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.ViewportLayout

internal class ViewportLayouts(types: DebugTypes) {
    private val pixelX = types.scalarMember("PixelPosition", "x", 4uL, 6u)
    private val pixelY = types.scalarMember("PixelPosition", "y", 4uL, 6u)
    private val width = types.scalarMember("PixelSize", "width", 4uL, 6u)
    private val height = types.scalarMember("PixelSize", "height", 4uL, 6u)
    private val fixed = "FixedPointNumberTemplate<int,8,0>"
    private val value = types.scalarMember(fixed, "value", 4uL, 6u)
    private val mapX = types.namedMember("MapPosition", "x", fixed, 4uL) + value
    private val mapY = types.namedMember("MapPosition", "y", fixed, 4uL) + value
    private val surface =
        types.path(
            "GameView",
            "cachedSurfaceView",
            "surfaceIndex",
            target = "SurfaceIndex",
            bytes = 4uL,
        ) + types.scalarMember("SurfaceIndex", "index", 4uL, 7u)
    private val position =
        types.path(
            "GameView",
            "cachedSurfaceView",
            "surfaceViewData",
            "position",
            "base",
            target = "MapPosition",
            bytes = 8uL,
        )

    init {
        for (type in listOf("PixelPosition", "PixelSize", "MapPosition")) {
            check(types.aggregateSize(type) == 8uL) { "Unsupported viewport value ABI: $type" }
        }
    }

    fun write(target: ViewportLayout) {
        target.supported = 1u
        target.pixelX = pixelX
        target.pixelY = pixelY
        target.width = width
        target.height = height
        target.mapX = mapX
        target.mapY = mapY
        target.surfaceIndex = surface
        target.cachedX = position + mapX
        target.cachedY = position + mapY
    }
}
