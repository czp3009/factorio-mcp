@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxIdentityLayout

/** Shared provider dispatch and bounded original names, with no concrete widget allowlist. */
internal data class WidgetIdentityLayout(val interfaces: WidgetIdentityInterfaces, val names: PrototypeNameLayout) {
    fun verify(image: ElfImage, bias: Long, read: (Long, Int) -> ByteArray) {
        interfaces.evidence.verify(image, bias, read)
        names.evidence.verify(image, bias, read)
    }

    fun writeTo(output: FmLinuxIdentityLayout, bias: Long) {
        require(bias >= 0 && maxOf(interfaces.widgetType, interfaces.providerType) <= Long.MAX_VALUE - bias)
        output.widgetType = (interfaces.widgetType + bias).toULong()
        output.providerType = (interfaces.providerType + bias).toULong()
        output.prototypeSlot = interfaces.prototype.toUInt()
        output.qualitySlot = interfaces.quality.toUInt()
        output.name = names.offset.toUInt()
        output.minimumExtent = names.minimumExtent.toUInt()
        output.qualityBase = names.qualityBase.toUInt()
        output.qualitySize = names.qualitySize.toUInt()
        output.stringSize = names.string.size.toUInt()
        output.stringData = names.string.data.toUInt()
        output.stringLength = names.string.length.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage) = WidgetIdentityLayout(WidgetIdentityInterfaces.resolve(image), PrototypeNameLayout.resolve(image))
    }
}
