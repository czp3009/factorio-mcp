@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxNumberLayout

/** One game interface, discovered from matching RTTI, primary virtual entries and original return uses. */
internal data class WidgetNumberLayout(
    val widgetType: Long,
    val type: Long,
    val count: Int,
    val draw: Int,
    val zero: Int,
    val unknown: Int,
    val infinite: Int,
    private val functions: List<ElfImage.Symbol>,
    private val readonly: List<ElfImage.ReadonlyRange>,
    internal val pointers: Map<Long, Long>,
    private val scalars: Map<Long, Long>,
) {
    fun writeTo(output: FmLinuxNumberLayout, bias: Long) {
        require(bias >= 0 && maxOf(widgetType, type) <= Long.MAX_VALUE - bias)
        output.widgetType = (widgetType + bias).toULong()
        output.type = (type + bias).toULong()
        output.count = count.toUInt()
        output.draw = draw.toUInt()
        output.zero = zero.toUInt()
        output.unknown = unknown.toUInt()
        output.infinite = infinite.toUInt()
    }

    fun verify(image: ElfImage, bias: Long, read: (Long, Int) -> ByteArray) {
        require(bias >= 0)
        fun relocated(value: Long): Long {
            require(value >= 0 && value <= Long.MAX_VALUE - bias)
            return if (value == 0L) 0 else value + bias
        }
        fun word(value: Long) = ByteArray(8) { (value ushr (it * 8)).toByte() }
        fun compare(address: Long, bytes: ByteArray) {
            require(address > 0 && bytes.isNotEmpty() && bytes.size <= 16 * 1024 * 1024 &&
                    address <= Long.MAX_VALUE - bias - bytes.size)
            require(read(address + bias, bytes.size).contentEquals(bytes)) {
                "Live number interface evidence differs from the selected executable"
            }
        }
        for (function in functions) compare(function.address,
            image.functionBytes(function, function.size.toInt()).bytes(0, function.size.toInt()))
        for (range in readonly) {
            val bytes = image.virtualBytes(range.address, range.size).bytes(0, range.size.toInt())
            for ((address, target) in pointers) for (index in 0..7) {
                val offset = address + index - range.address
                if (offset in 0 until range.size) bytes[offset.toInt()] = word(relocated(target))[index]
            }
            compare(range.address, bytes)
        }
        for ((address, target) in pointers) compare(address, word(relocated(target)))
        for ((address, value) in scalars) compare(address, word(value))
    }

    companion object {
        fun resolve(image: ElfImage): WidgetNumberLayout {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val pointers = mutableMapOf<Long, Long>()
                    val scalars = mutableMapOf<Long, Long>()
                    fun type(encoded: String): ItaniumClass {
                        val result = ItaniumClass.resolve(image, encoded)
                        val symbol = image.symbol("_ZTI$encoded")
                        image.pointers.words(symbol.address, (symbol.size / 8).toInt()).forEachIndexed { index, word ->
                            val address = symbol.address + index * 8L
                            if (index < 2 || symbol.size == 24L || index >= 3 && index % 2 == 1)
                                pointers[address] = word.pointer()
                            else scalars[address] = word.scalar()
                        }
                        return result
                    }
                    val widget = type("N4agui6WidgetE")
                    val number = type("12ButtonNumber")
                    require(number.bases.isEmpty()) { "Number interface has unsupported inherited virtual entries" }
                    val symbol = image.symbol("_ZTV12ButtonNumber")
                    val table = ItaniumVtable.resolve(image, symbol.name)
                    image.pointers.words(symbol.address, (symbol.size / 8).toInt()).forEachIndexed { index, word ->
                        if (index == 0) scalars[symbol.address] = word.scalar()
                        else pointers[symbol.address + index * 8L] = word.pointer()
                    }
                    fun flag(name: String): Int {
                        val method = table.method(image, "_ZNK12ButtonNumber$name")
                        BooleanLeafReturn.analyze(image.functionBytes(method.function, 256))
                        return method.slot
                    }
                    val count = table.method(image, "__cxa_pure_virtual").slot
                    val paint = image.symbol("_ZN12ButtonNumber11paintNumberEPKN4agui6WidgetERKNS0_10PaintEventE")
                    EhFrames(image).function(paint)
                    NumberCountCall.analyze(image.functionBytes(paint, 8192), paint.address, count)
                    val result = WidgetNumberLayout(widget.typeInfo, number.typeInfo, count,
                        flag("16shouldDrawNumberEv"), flag("14shouldShowZeroEv"), flag("9isUnknownEv"),
                        flag("10isInfiniteEv"), emptyList(), emptyList(), pointers, scalars)
                    require(listOf(result.count, result.draw, result.zero, result.unknown, result.infinite)
                        .let { it.distinct().size == it.size && it.all { slot -> slot in 0..255 } })
                    result
                }
            }
            return resolved.first.copy(functions = resolved.second, readonly = readonly)
        }
    }
}
