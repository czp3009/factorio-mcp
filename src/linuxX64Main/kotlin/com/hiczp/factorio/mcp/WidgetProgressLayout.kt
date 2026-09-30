package com.hiczp.factorio.mcp

internal data class WidgetProgressLayout(
    val table: Long, val size: Long, val value: Long,
    val direction: Long, val hasText: Long
) {
    companion object {
        fun resolve(image: ElfImage, concreteType: String, fields: WidgetProgressFields): WidgetProgressLayout {
            val extent = maxOf(fields.value + 8, fields.direction + 1, fields.hasText + 1)
            val type = WidgetPropertyType.resolve(image, concreteType, "N4agui11ProgressBarE", extent)
            return WidgetProgressLayout(
                type.table, type.size, type.baseOffset + fields.value,
                type.baseOffset + fields.direction, type.baseOffset + fields.hasText
            )
        }
    }
}
