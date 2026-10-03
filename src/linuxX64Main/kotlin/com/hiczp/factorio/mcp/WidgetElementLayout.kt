@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxElementLayout

/** Existing optional UI elements and their raw item properties; no materialization or inferred defaults. */
internal data class WidgetElementLayout(val interfaces: WidgetElementInterfaces, val items: WidgetItemLayout) {
    fun verify(image: ElfImage, bias: Long, read: (Long, Int) -> ByteArray) {
        interfaces.evidence.verify(image, bias, read)
        items.evidence.verify(image, bias, read)
    }

    fun writeTo(output: FmLinuxElementLayout, bias: Long) {
        val types = listOf(interfaces.widgetType, interfaces.stackProvider, interfaces.itemProvider,
            items.itemType, items.toolType, items.ammoType)
        require(bias >= 0 && types.all { it > 0 && it <= Long.MAX_VALUE - bias })
        output.widgetType = (interfaces.widgetType + bias).toULong()
        output.stackProvider = (interfaces.stackProvider + bias).toULong()
        output.itemProvider = (interfaces.itemProvider + bias).toULong()
        output.itemType = (items.itemType + bias).toULong()
        output.toolType = (items.toolType + bias).toULong()
        output.ammoType = (items.ammoType + bias).toULong()
        output.stackGetter = interfaces.stackGetter.toUInt()
        output.itemGetter = interfaces.itemGetter.toUInt()
        output.stackExtent = items.stackExtent.toUInt()
        output.itemSize = items.itemSize.toUInt()
        output.toolSize = items.toolSize.toUInt()
        output.ammoSize = items.ammoSize.toUInt()
        output.stackItem = items.stackItem.toUInt()
        output.count = items.count.toUInt()
        output.health = items.health.toUInt()
        output.durability = items.durability.toUInt()
        output.magazine = items.magazine.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage) = WidgetElementLayout(WidgetElementInterfaces.resolve(image), WidgetItemLayout.resolve(image))
    }
}
