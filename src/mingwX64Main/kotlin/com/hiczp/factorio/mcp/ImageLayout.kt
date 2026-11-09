@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.FmUiImageLayout

/** Read retained image identity; no animation accessor or rendering code is invoked. */
internal class ImageLayout(types: DebugTypes, descriptors: Map<String, List<ULong>>) {
    private val type = descriptors.getValue("??_R0?AVImageWidget@agui@@@8").single()
    private val imageType = descriptors.getValue("??_R0?AVImage@agui@@@8").single()
    private val staticType = descriptors.getValue("??_R0?AVGuiImage@@@8").single()
    private val widgetSize = types.aggregateSize("agui::ImageWidget")
    private val imageSize = types.aggregateSize("agui::Image")
    private val staticSize = types.aggregateSize("GuiImage")
    private val image = types.pointerPath(
        "agui::ImageWidget", "image", "_Mypair", "_Myval2", target = "agui::Image", indirections = 1,
    )
    private val sprite = types.pointerMember("GuiImage", "sprite", "Sprite")

    init {
        require(widgetSize in 8uL..16uL * 1024u * 1024u && imageSize in 8uL..staticSize && staticSize <= 65536uL)
        require(types.directBaseOffset("GuiImage", "FactorioImage") == 0u)
        require(types.directBaseOffset("FactorioImage", "agui::Image") == 0u)
    }

    fun write(output: FmUiImageLayout) {
        output.type = type
        output.imageType = imageType
        output.staticType = staticType
        output.supported = 1u
        output.widgetSize = widgetSize.toUInt()
        output.image = image
        output.imageSize = imageSize.toUInt()
        output.staticSize = staticSize.toUInt()
        output.sprite = sprite
    }
}
