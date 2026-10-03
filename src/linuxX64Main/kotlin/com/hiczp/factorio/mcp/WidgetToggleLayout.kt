package com.hiczp.factorio.mcp

/** A bounded byte getter identified by its live virtual entry, including inherited button implementations. */
internal data class WidgetToggleLayout(
    val slot: Int, val function: ElfImage.Symbol, val offset: Long, val size: Long,
    val tables: List<Long>, val modeFunction: ElfImage.Symbol, val modeOffset: Long
) {
    companion object {
        fun resolve(image: ElfImage): WidgetToggleLayout {
            val function = image.symbol("_ZNK4agui6Button9isToggledEv")
            val size = SysVObjectSize.resolve(image, "4agui6Button")
            val accessor = SysVAccessors.resolve(image, function).withinObject(size)
            require(accessor.width == 1 && accessor.mask == 255uL && accessor.shift == 0)
            val modeFunction = image.symbol("_ZNK4agui6Button14isToggleButtonEv")
            val mode = SysVAccessors.resolve(image, modeFunction).withinObject(size)
            require(mode.width == 1 && mode.mask == 255uL && mode.shift == 0)
            val slot = ItaniumVtable.resolve(image, "_ZTVN4agui6ButtonE").method(image, function.name).slot
            val words = image.pointers
            val references = if (image.positionIndependent) words.relativeReferences(function.address) else null
            val tables = image.symbols().filter {
                it.type == 1 && it.name.startsWith("_ZTV") &&
                        it.size >= 16 + (slot + 1) * 8L &&
                        (references == null || it.address + 16 + slot * 8L in references)
            }.distinctBy { it.address }.filter {
                runCatching {
                    words.words(it.address + 16 + slot * 8L, 1).single().pointer()
                }.getOrNull() == function.address
            }.mapNotNull {
                // Unsupported secondary/virtual-base groups remain unknown properties, not guessed layouts.
                val table = runCatching { ItaniumVtable.resolve(image, it.name, words) }.getOrNull()
                    ?: return@mapNotNull null
                require(table.method(image, function.name).slot == slot)
                table.addressPoint
            }.toList()
            require(tables.size in 1..1024)
            return WidgetToggleLayout(slot, function, accessor.offset, size, tables, modeFunction, mode.offset)
        }
    }
}
