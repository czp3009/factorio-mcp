package com.hiczp.factorio.mcp

/** Non-split x64 DWARF 4/5 code ranges. Addresses and lengths retain their distinct form classes. */
internal class DwarfRanges(private val image: ElfImage, private val info: DwarfInfo) {
    data class Range(val start: Long, val end: Long) {
        init {
            require(start >= 0 && end > start)
        }

        fun contains(other: Range) = start <= other.start && other.end <= end
    }

    private data class Contribution(
        val end: Long,
        val width: Int,
        val offsets: Long,
        val count: Long,
        val entries: Long
    )

    private val rangeContributions by lazy { contributions(image.section(".debug_rnglists"), true) }
    private val addressContributions by lazy { contributions(image.section(".debug_addr"), false) }

    fun ranges(entry: DwarfInfo.Entry): List<Range> {
        val unit = info.unit(entry.offset)
        val root = info.root(unit)
        val low = address(entry, 0x11)
        if (0x55 in entry.attributes) {
            require(0x12 !in entry.attributes) { "DWARF entry has both high_pc and ranges" }
            val base = address(root, 0x11)
            val value = entry.attributes.getValue(0x55)
            if (unit.version == 4) {
                require(entry.forms[0x55] == 0x17 && value is DwarfInfo.Value.Number)
                return decode4(image.section(".debug_ranges"), value.value, base)
            }
            val section = image.section(".debug_rnglists")
            val selected: Contribution
            val offset: Long
            if (entry.forms[0x55] == 0x23 && value is DwarfInfo.Value.Index) {
                val table = root.number(0x74) ?: error("Missing DWARF range-list base")
                require(root.forms[0x74] == 0x17)
                selected = rangeContributions.singleOrNull { it.offsets == table }
                    ?: error("Range-list base is not an offsets table")
                require(value.index in 0 until selected.count && unit.offsetSize == selected.width)
                offset = add(table, section.unsigned(table + value.index * selected.width, selected.width))
            } else {
                require(entry.forms[0x55] == 0x17 && value is DwarfInfo.Value.Number)
                offset = value.value
                selected = rangeContributions.singleOrNull { offset in it.entries until it.end }
                    ?: error("Range list is outside its contribution")
            }
            require(unit.offsetSize == selected.width && offset in selected.entries until selected.end)
            return decode5(section.slice(0, selected.end), offset, base) { indexedAddress(unit, it) }
        }
        if (0x12 !in entry.attributes) return emptyList()
        require(low != null) { "DWARF high_pc lacks low_pc" }
        val form = entry.forms[0x12]
        val high = if (form in addressForms) checkNotNull(address(entry, 0x12)) else {
            require(form in constantForms) { "Unsupported DWARF high_pc class" }
            add(low, (entry.attributes[0x12] as? DwarfInfo.Value.Number)?.value ?: error("Non-scalar high_pc"))
        }
        require(high >= low) { "Inverted DWARF code range" }
        return if (low == high) emptyList() else listOf(Range(low, high))
    }

    private fun address(entry: DwarfInfo.Entry, attribute: Int): Long? {
        val value = entry.attributes[attribute] ?: return null
        require(entry.forms[attribute] in addressForms) { "DWARF address has a non-address form" }
        return when (value) {
            is DwarfInfo.Value.Number -> value.value.also { require(it >= 0) }
            is DwarfInfo.Value.Index -> {
                require(value.form == 0x1b)
                indexedAddress(info.unit(entry.offset), value.index)
            }

            else -> error("Non-scalar DWARF address")
        }
    }

    private fun indexedAddress(unit: DwarfInfo.Unit, index: Long): Long {
        require(unit.version == 5)
        val root = info.root(unit)
        val base = root.number(0x73) ?: error("Missing DWARF address-table base")
        require(root.forms[0x73] == 0x17)
        val table = addressContributions.singleOrNull { it.entries == base }
            ?: error("Address base is not a contribution's first entry")
        require(table.width == unit.offsetSize && index >= 0 && index < (table.end - base) / 8)
        return image.section(".debug_addr").unsigned(base + index * 8, 8).also { require(it >= 0) }
    }

    private fun contributions(section: BinaryView, ranges: Boolean): List<Contribution> {
        val result = mutableListOf<Contribution>()
        val cursor = section.cursor()
        while (cursor.remaining > 0) {
            var length = cursor.unsigned(4)
            val width = if (length == 0xffffffffL) 8 else 4
            if (width == 8) length = cursor.unsigned(8) else require(length < 0xfffffff0L)
            require(length > 0 && length <= cursor.remaining)
            val end = cursor.position + length
            val header = section.slice(0, end).cursor(cursor.position)
            require(header.unsigned(2) == 5L && header.unsigned(1) == 8L && header.unsigned(1) == 0L) {
                "Unsupported DWARF range/address contribution header"
            }
            val count = if (ranges) header.unsigned(4) else 0
            require(count in 0..65536 && count <= header.remaining / width)
            val offsets = header.position
            header.skip(count * width)
            require(ranges || header.remaining % 8 == 0L)
            result += Contribution(end, width, offsets, count, header.position)
            require(result.size <= 65536)
            cursor.skip(end - cursor.position)
        }
        return result
    }

    companion object {
        private val addressForms = setOf(0x01, 0x1b, 0x29, 0x2a, 0x2b, 0x2c)
        private val constantForms = setOf(0x05, 0x06, 0x07, 0x0b, 0x0d, 0x0f, 0x21)

        private fun add(base: Long, offset: Long): Long {
            require(base >= 0 && offset >= 0 && offset <= Long.MAX_VALUE - base) { "DWARF address overflows" }
            return base + offset
        }

        private fun normalized(ranges: List<Range>): List<Range> {
            val result = mutableListOf<Range>()
            for (range in ranges.sortedBy { it.start }) {
                val previous = result.lastOrNull()
                if (previous != null && range.start <= previous.end) result[result.lastIndex] =
                    Range(previous.start, maxOf(previous.end, range.end))
                else result += range
            }
            return result
        }

        fun decode4(section: BinaryView, offset: Long, initialBase: Long?): List<Range> {
            require(initialBase == null || initialBase >= 0)
            val cursor = section.cursor(offset)
            var base = initialBase
            val result = mutableListOf<Range>()
            repeat(1048576) {
                val start = cursor.unsigned(8)
                val end = cursor.unsigned(8)
                if (start == 0L && end == 0L) return normalized(result)
                if (start == -1L) base = end.also { require(it >= 0) } else {
                    require(start >= 0 && end >= start)
                    if (start != end) result += Range(add(checkNotNull(base), start), add(checkNotNull(base), end))
                }
            }
            error("Unterminated DWARF 4 range list")
        }

        fun decode5(section: BinaryView, offset: Long, initialBase: Long?, address: (Long) -> Long): List<Range> {
            require(initialBase == null || initialBase >= 0)
            val cursor = section.cursor(offset)
            var base = initialBase
            val result = mutableListOf<Range>()
            fun index(): Long = cursor.uleb().also { require(it >= 0) }.let(address).also { require(it >= 0) }
            repeat(1048576) {
                val pair = when (val kind = cursor.unsigned(1).toInt()) {
                    0 -> return normalized(result)
                    1 -> {
                        base = index()
                        null
                    }

                    2 -> index() to index()
                    3 -> index().let { it to add(it, cursor.uleb()) }
                    4 -> add(checkNotNull(base), cursor.uleb()) to add(checkNotNull(base), cursor.uleb())
                    5 -> {
                        base = cursor.unsigned(8).also { require(it >= 0) }
                        null
                    }

                    6 -> cursor.unsigned(8) to cursor.unsigned(8)
                    7 -> cursor.unsigned(8).let { it to add(it, cursor.uleb()) }
                    else -> error("Unknown DWARF range-list entry: $kind")
                }
                if (pair != null) {
                    require(pair.first >= 0 && pair.second >= pair.first)
                    if (pair.first != pair.second) result += Range(pair.first, pair.second)
                }
            }
            error("Unterminated DWARF 5 range list")
        }
    }
}
