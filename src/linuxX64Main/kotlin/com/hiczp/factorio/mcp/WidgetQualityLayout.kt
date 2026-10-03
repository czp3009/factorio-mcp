@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxQualityLayout

internal data class WidgetQualityLayout(val fields: QualityConditionFields, val returned: QualityConditionReturn) {
    fun verify(image: ElfImage, bias: Long, read: (Long, Int) -> ByteArray) {
        fields.evidence.verify(image, bias, read)
        returned.evidence.verify(image, bias, read)
    }

    fun writeTo(output: FmLinuxQualityLayout, widgetType: ULong, bias: Long) {
        require(bias >= 0 && listOf(fields.registry, returned.provider).all { it > 0 && it <= Long.MAX_VALUE - bias })
        output.widgetType = widgetType
        output.providerType = (returned.provider + bias).toULong()
        output.registry = (fields.registry + bias).toULong()
        output.registrySize = fields.registrySize.toUInt()
        output.first = fields.registryBegin.toUInt()
        output.last = fields.registryEnd.toUInt()
        output.getter = returned.getter.toUInt()
        output.width = returned.width.toUInt()
        output.quality = returned.quality.toUInt()
        output.comparison = returned.comparison.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage): WidgetQualityLayout {
            val fields = QualityConditionFields.resolve(image)
            return WidgetQualityLayout(fields, QualityConditionReturn.resolve(image, fields))
        }
    }
}
