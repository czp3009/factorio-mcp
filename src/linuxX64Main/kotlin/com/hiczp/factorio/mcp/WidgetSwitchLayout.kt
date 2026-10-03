package com.hiczp.factorio.mcp

internal data class WidgetSwitchLayout(val table: Long, val size: Long, val state: Long, val allowNone: Long) {
    companion object {
        fun resolve(image: ElfImage, concreteType: String, fields: WidgetSwitchState): WidgetSwitchLayout {
            val type = WidgetPropertyType.resolve(image, concreteType, "N4agui6SwitchE", fields.size)
            return WidgetSwitchLayout(
                type.table, type.size, type.baseOffset + fields.state, type.baseOffset + fields.allowNone,
            )
        }
    }
}
