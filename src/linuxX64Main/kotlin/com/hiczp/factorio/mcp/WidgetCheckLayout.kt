package com.hiczp.factorio.mcp

/** Concrete primary-object bounds for the independently named ToggleButton predicate. */
internal data class WidgetCheckLayout(val table: Long, val size: Long, val stateOffset: Long, val checkedValue: Int) {
    companion object {
        fun resolve(image: ElfImage, concreteType: String, predicate: WidgetCheckPredicate): WidgetCheckLayout {
            val type = WidgetPropertyType.resolve(image, concreteType, "N4agui12ToggleButtonE", predicate.offset + 4)
            predicate.withinObject(type.size - type.baseOffset)
            return WidgetCheckLayout(type.table, type.size, type.baseOffset + predicate.offset, predicate.checkedValue)
        }
    }
}
