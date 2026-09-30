package com.hiczp.factorio.mcp

internal data class WidgetDropdownLayout(
    val table: Long, val size: Long, val selected: Long,
    val first: Long, val last: Long, val stride: Long, val button: Long
) {
    companion object {
        fun resolve(image: ElfImage, concreteType: String, fields: WidgetDropdownFields): WidgetDropdownLayout {
            val extent = maxOf(fields.selected + 4, fields.first + 8, fields.last + 8)
            val type = WidgetPropertyType.resolve(image, concreteType, "N4agui8DropDownE", extent)
            return WidgetDropdownLayout(
                type.table, type.size, type.baseOffset + fields.selected,
                type.baseOffset + fields.first, type.baseOffset + fields.last, fields.stride, fields.button
            )
        }
    }
}
