package com.hiczp.factorio.mcp

/** Stored Button flags, including derived and secondary subobjects; virtual overrides are not evaluated. */
internal data class WidgetToggleLayout(
    val offset: Long,
    val size: Long,
    val modeOffset: Long,
    val widgetType: Long,
    val buttonType: Long,
    val evidence: ElfEvidence,
) {
    companion object {
        fun resolve(image: ElfImage): WidgetToggleLayout {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val function = image.symbol("_ZNK4agui6Button9isToggledEv")
                    val size = SysVObjectSize.resolve(image, "4agui6Button")
                    val accessor = SysVAccessors.resolve(image, function).withinObject(size)
                    require(accessor.width == 1 && accessor.mask == 255uL && accessor.shift == 0)
                    ItaniumVtable.resolve(image, "_ZTVN4agui6ButtonE").method(image, function.name)
                    val mode = SysVAccessors.resolve(image, image.symbol("_ZNK4agui6Button14isToggleButtonEv"))
                        .withinObject(size)
                    require(mode.width == 1 && mode.mask == 255uL && mode.shift == 0 && mode.offset != accessor.offset)
                    val ancestry = ItaniumSubobjectVtable.resolve(image, "N4agui6ButtonE", "N4agui6WidgetE", size)
                    val widget = ItaniumClass.resolve(image, "N4agui6WidgetE")
                    val button = ItaniumClass.resolve(image, "N4agui6ButtonE")
                    WidgetToggleLayout(
                        accessor.offset, size, mode.offset, widget.typeInfo, button.typeInfo,
                        ElfEvidence(emptyList(), emptyList(), ancestry.pointers, ancestry.scalars),
                    )
                }
            }
            return resolved.first.copy(
                evidence = resolved.first.evidence.copy(functions = resolved.second, readonly = readonly)
            )
        }
    }
}
