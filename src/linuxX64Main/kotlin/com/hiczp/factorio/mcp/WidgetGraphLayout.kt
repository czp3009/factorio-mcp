@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmUiGraphLayout
import com.hiczp.factorio.mcp.linuxbridge.FmUiGraphVectorLayout

internal data class WidgetGraphLayout(val type: Long, val size: Long, val fields: GraphStorageFields, val evidence: ElfEvidence) {
    fun writeTo(output: FmUiGraphLayout, bias: Long) {
        require(bias >= 0 && type <= Long.MAX_VALUE - bias)
        output.supported = 1u
        output.type = (type + bias).toULong()
        output.size = size.toUInt()
        output.sampleCount = fields.sampleCount.toUInt()
        output.minimum = fields.minimum.toUInt()
        output.maximum = fields.maximum.toUInt()
        fun GraphStorageFields.Vector.write(v: FmUiGraphVectorLayout) {
            v.first = first.toUInt()
            v.last = last.toUInt()
            v.end = end.toUInt()
            v.stride = stride.toUInt()
        }
        fields.series.write(output.series)
        fields.values.write(output.values)
        output.seriesLabel = fields.label.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage): WidgetGraphLayout {
            val types = NativeTypeEvidence(image)
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val type = types.type("N4agui5GraphE")
                    val widget = types.type("N4agui6WidgetE")
                    val size = SysVObjectSize.resolve(image, "4agui5Graph")
                    require(type.directBase(widget, size, 8) == 0L)
                    val fields = GraphStorageFields.resolve(image, size, NativeStringLayout.resolve(image))
                    type.typeInfo to (size to fields)
                }
            }
            val value = resolved.first
            return WidgetGraphLayout(value.first, value.second.first, value.second.second,
                types.evidence().copy(functions = resolved.second, readonly = readonly))
        }
    }
}
