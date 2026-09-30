package com.hiczp.factorio.mcp

internal data class WidgetSliderLayout(
    val table: Long, val size: Long, val value: Long, val minimum: Long,
    val maximum: Long, val step: Long
) {
    companion object {
        fun resolve(image: ElfImage, concreteType: String, fields: WidgetSliderFields): WidgetSliderLayout {
            val offsets = listOf(fields.value, fields.minimum, fields.maximum, fields.step)
            val type = WidgetPropertyType.resolve(image, concreteType, "N4agui6SliderE", offsets.max() + 8)
            return WidgetSliderLayout(
                type.table, type.size, type.baseOffset + fields.value,
                type.baseOffset + fields.minimum, type.baseOffset + fields.maximum, type.baseOffset + fields.step
            )
        }
    }
}
