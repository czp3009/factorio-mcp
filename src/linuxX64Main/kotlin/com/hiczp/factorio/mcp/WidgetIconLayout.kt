@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxIconLayout

/** The native widget's three references; image storage is outside the observation contract. */
internal data class WidgetIconLayout(
    val widgetType: Long,
    val type: Long,
    val size: Long,
    val fields: IconSpriteFields,
    val evidence: ElfEvidence,
) {
    fun writeTo(output: FmLinuxIconLayout, bias: Long) {
        require(bias >= 0 && maxOf(widgetType, type) <= Long.MAX_VALUE - bias)
        output.widgetType = (widgetType + bias).toULong()
        output.type = (type + bias).toULong()
        output.iconSize = size.toUInt()
        output.normal = fields.normal.toUInt()
        output.hovered = fields.hovered.toUInt()
        output.disabled = fields.disabled.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage): WidgetIconLayout {
            val (resolved, readonly) =
                image.withReadonlyEvidence {
                    image.withFunctionEvidence {
                        val size = SysVObjectSize.resolve(image, "10IconButton")
                        val widget = ItaniumClass.resolve(image, "N4agui6WidgetE")
                        val icon = ItaniumClass.resolve(image, "10IconButton")
                        val fields = IconSpriteFields.resolve(image, size)
                        WidgetIconLayout(
                            widget.typeInfo,
                            icon.typeInfo,
                            size,
                            fields,
                            ElfEvidence(emptyList(), emptyList(), emptyMap(), emptyMap()),
                        )
                    }
                }
            // RTTI words contain relocated pointers and cannot be compared as ordinary readonly
            // bytes.
            val pointers = mutableMapOf<Long, Long>()
            val scalars = mutableMapOf<Long, Long>()
            for (name in listOf("_ZTIN4agui6WidgetE", "_ZTI10IconButton")) {
                val symbol = image.symbol(name)
                image.pointers.words(symbol.address, (symbol.size / 8).toInt()).forEachIndexed {
                    index,
                    word ->
                    val address = symbol.address + index * 8L
                    if (index < 2 || symbol.size == 24L || index >= 3 && index % 2 == 1)
                        pointers[address] = word.pointer()
                    else scalars[address] = word.scalar()
                }
            }
            val table = image.symbol("_ZTV10IconButton")
            image.pointers.words(table.address, (table.size / 8).toInt()).forEachIndexed {
                index,
                word ->
                if (index == 0) scalars[table.address] = word.scalar()
                else pointers[table.address + index * 8L] = word.pointer()
            }
            return resolved.first.copy(
                evidence = ElfEvidence(resolved.second, readonly, pointers, scalars)
            )
        }
    }
}
