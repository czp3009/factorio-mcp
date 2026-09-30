package com.hiczp.factorio.mcp

/** DWARF 4/5 DIE reader. Unsupported encodings fail before any process mutation. */
internal class DwarfInfo(private val image: ElfImage) {
    data class Unit(
        val start: Long,
        val end: Long,
        val entries: Long,
        val version: Int,
        val offsetSize: Int,
        val abbreviations: Long,
    )

    sealed interface Value {
        data class Number(val value: Long) : Value
        data class Text(val value: String) : Value
        data class Reference(val offset: Long) : Value
        data class Block(val bytes: BinaryView) : Value
        data class Index(val form: Int, val index: Long) : Value
    }

    data class Entry(
        val offset: Long,
        val next: Long,
        val tag: Int,
        val hasChildren: Boolean,
        val attributes: Map<Int, Value>,
        val forms: Map<Int, Int> = emptyMap(),
    ) {
        fun number(attribute: Int): Long? = (attributes[attribute] as? Value.Number)?.value

        fun reference(attribute: Int): Long? = (attributes[attribute] as? Value.Reference)?.offset
    }

    private data class Attribute(val name: Int, val form: Int, val constant: Long)

    private data class Abbreviation(val tag: Int, val children: Boolean, val attributes: List<Attribute>)

    private val info = image.section(".debug_info")
    private val abbreviations = image.section(".debug_abbrev")
    private val strings by lazy { image.section(".debug_str") }
    private val lineStrings by lazy { image.section(".debug_line_str") }
    private val stringOffsets by lazy { image.section(".debug_str_offsets") }
    private val addresses by lazy { image.section(".debug_addr") }
    private val abbreviationCache = mutableMapOf<Long, Map<Long, Abbreviation>>()
    private val roots = mutableMapOf<Long, Entry>()
    val units: List<Unit> = readUnits()

    private fun readUnits(): List<Unit> {
        val result = mutableListOf<Unit>()
        val cursor = info.cursor()
        while (cursor.remaining > 0) {
            val start = cursor.position
            var length = cursor.unsigned(4)
            val width = if (length == 0xffffffffL) 8 else 4
            if (width == 8) length = cursor.unsigned(8)
            else require(length < 0xfffffff0L) { "Reserved DWARF unit length" }
            require(length > 0 && length <= cursor.remaining) { "Invalid DWARF unit length" }
            val end = cursor.position + length
            val header = cursor.block(length).cursor()
            val version = header.unsigned(2).toInt()
            require(version == 4 || version == 5) { "Unsupported DWARF version: $version" }
            val table: Long
            if (version == 5) {
                require(header.unsigned(1) == 1L) { "Split or type DWARF units are unsupported" }
                require(header.unsigned(1) == 8L) { "Expected x64 DWARF address size" }
                table = header.unsigned(width)
            } else {
                table = header.unsigned(width)
                require(header.unsigned(1) == 8L) { "Expected x64 DWARF address size" }
            }
            abbreviations.range(table, 1)
            require(header.remaining > 0) { "Empty DWARF compilation unit" }
            result += Unit(start, end, end - header.remaining, version, width, table)
            require(result.size <= 65536) { "DWARF compilation unit count exceeds bound" }
        }
        require(result.isNotEmpty()) { "No DWARF compilation units" }
        return result
    }

    fun unit(offset: Long): Unit {
        var low = 0
        var high = units.size
        while (low < high) {
            val middle = (low + high) / 2
            if (units[middle].end <= offset) low = middle + 1 else high = middle
        }
        return units.getOrNull(low)?.takeIf { offset >= it.entries }
            ?: error("DWARF reference is outside a compilation unit")
    }

    private fun table(unit: Unit): Map<Long, Abbreviation> =
        abbreviationCache.getOrPut(unit.abbreviations) {
            val cursor = abbreviations.cursor(unit.abbreviations)
            val result = mutableMapOf<Long, Abbreviation>()
            while (true) {
                val code = cursor.uleb()
                if (code == 0L) break
                require(code > 0 && result.size < 65536 && code !in result) { "Invalid DWARF abbreviation code" }
                val tag = cursor.uleb().small("tag")
                val children = cursor.unsigned(1)
                require(children in 0..1) { "Invalid DWARF children flag" }
                val attributes = mutableListOf<Attribute>()
                while (true) {
                    val name = cursor.uleb().small("attribute")
                    val form = cursor.uleb().small("form")
                    if (name == 0 && form == 0) break
                    require(name != 0 && form != 0 && attributes.size < 256 && attributes.none { it.name == name }) {
                        "Invalid DWARF abbreviation attributes"
                    }
                    attributes += Attribute(name, form, if (form == 0x21) cursor.sleb() else 0)
                }
                result[code] = Abbreviation(tag, children == 1L, attributes)
            }
            result
        }

    fun entry(offset: Long): Entry = entry(unit(offset), offset)

    private fun entry(unit: Unit, offset: Long, skipAttributes: Boolean = false): Entry {
        require(offset in unit.entries until unit.end)
        val cursor = info.slice(0, unit.end).cursor(offset)
        val code = cursor.uleb()
        if (code == 0L) return Entry(offset, cursor.position, 0, false, emptyMap())
        val abbreviation = table(unit)[code] ?: error("Missing DWARF abbreviation: $code")
        val forms = mutableMapOf<Int, Int>()
        val values = mutableMapOf<Int, Value>()
        for (attribute in abbreviation.attributes) {
            var form = attribute.form
            var depth = 0
            while (form == 0x16) {
                require(++depth <= 8) { "DWARF indirect form recursion exceeds bound" }
                form = cursor.uleb().small("indirect form")
                require(form != 0x21) { "Indirect implicit constants have no abbreviation value" }
            }
            if (skipAttributes && attribute.name != 0x01) skipValue(cursor, unit, form) else {
                forms[attribute.name] = form
                values[attribute.name] = value(cursor, unit, form, attribute.constant)
            }
        }
        return Entry(offset, cursor.position, abbreviation.tag, abbreviation.children, values, forms)
    }

    /** Only encoding/bounds matter in an unselected subtree; do not materialize its strings or attribute maps. */
    private fun skipValue(cursor: BinaryCursor, unit: Unit, form: Int) {
        when (form) {
            0x01, 0x07, 0x14 -> cursor.skip(8)
            0x03 -> cursor.skip(cursor.unsigned(2))
            0x04 -> cursor.skip(cursor.unsigned(4))
            0x05, 0x12 -> cursor.skip(2)
            0x06, 0x13 -> cursor.skip(4)
            0x08 -> cursor.string()
            0x09, 0x18 -> cursor.skip(cursor.uleb())
            0x0a -> cursor.skip(cursor.unsigned(1))
            0x0b, 0x11 -> cursor.skip(1)
            0x0c -> require(cursor.unsigned(1) in 0..1) { "Invalid DWARF flag" }
            0x0d -> cursor.sleb()
            0x0e, 0x10, 0x17, 0x1f -> cursor.skip(unit.offsetSize.toLong())
            0x0f, 0x15, 0x1a, 0x1b, 0x22, 0x23 -> cursor.uleb()
            0x19, 0x21 -> {}
            0x1e -> cursor.skip(16)
            in 0x25..0x28 -> cursor.skip((form - 0x24).toLong())
            in 0x29..0x2c -> cursor.skip((form - 0x28).toLong())
            else -> error("Unsupported DWARF form: 0x${form.toString(16)}")
        }
    }

    private fun value(cursor: BinaryCursor, unit: Unit, form: Int, constant: Long): Value {
        fun reference(value: Long): Value.Reference {
            require(value >= unit.entries - unit.start && value < unit.end - unit.start) { "Invalid local DWARF reference" }
            return Value.Reference(unit.start + value)
        }
        return when (form) {
            0x01 -> Value.Number(cursor.unsigned(8)) // addr
            0x03 -> Value.Block(cursor.block(cursor.unsigned(2)))
            0x04 -> Value.Block(cursor.block(cursor.unsigned(4)))
            0x05 -> Value.Number(cursor.unsigned(2))
            0x06 -> Value.Number(cursor.unsigned(4))
            0x07 -> Value.Number(cursor.unsigned(8))
            0x08 -> Value.Text(cursor.string())
            0x09 -> Value.Block(cursor.block(cursor.uleb()))
            0x0a -> Value.Block(cursor.block(cursor.unsigned(1)))
            0x0b -> Value.Number(cursor.unsigned(1))
            0x0c -> Value.Number(cursor.unsigned(1).also { require(it in 0..1) { "Invalid DWARF flag" } })
            0x0d -> Value.Number(cursor.sleb())
            0x0e -> Value.Text(strings.string(cursor.unsigned(unit.offsetSize)))
            0x0f -> Value.Number(cursor.uleb())
            0x10 -> Value.Reference(cursor.unsigned(unit.offsetSize).also { this.unit(it) })
            0x11 -> reference(cursor.unsigned(1))
            0x12 -> reference(cursor.unsigned(2))
            0x13 -> reference(cursor.unsigned(4))
            0x14 -> reference(cursor.unsigned(8))
            0x15 -> reference(cursor.uleb())
            0x17 -> Value.Number(cursor.unsigned(unit.offsetSize))
            0x18 -> Value.Block(cursor.block(cursor.uleb()))
            0x19 -> Value.Number(1)
            0x1a, 0x1b, 0x22, 0x23 -> Value.Index(form, cursor.uleb())
            0x1e -> Value.Block(cursor.block(16))
            0x1f -> Value.Text(lineStrings.string(cursor.unsigned(unit.offsetSize)))
            0x21 -> Value.Number(constant)
            in 0x25..0x28 -> Value.Index(0x1a, cursor.unsigned(form - 0x24))
            in 0x29..0x2c -> Value.Index(0x1b, cursor.unsigned(form - 0x28))
            else -> error("Unsupported DWARF form: 0x${form.toString(16)}")
        }
    }

    fun root(unit: Unit): Entry = roots.getOrPut(unit.start) { entry(unit, unit.entries) }

    fun releaseAbbreviations() = abbreviationCache.clear()

    fun attribute(entry: Entry, name: Int): Value? {
        val seen = mutableSetOf<Long>()
        fun lookup(current: Entry): Value? {
            require(seen.add(current.offset) && seen.size <= 64) { "Cyclic DWARF specification/origin" }
            current.attributes[name]?.let { value ->
                if (value !is Value.Index) return value
                val unit = unit(current.offset)
                fun indexed(section: BinaryView, baseAttribute: Int, width: Int): Long {
                    val base = root(unit).number(baseAttribute) ?: error("Missing DWARF indexed attribute base")
                    require(base >= 0 && value.index >= 0 && value.index <= (Long.MAX_VALUE - base) / width) {
                        "DWARF indexed attribute overflow"
                    }
                    return section.unsigned(base + value.index * width, width)
                }
                return when (value.form) {
                    0x1a -> Value.Text(strings.string(indexed(stringOffsets, 0x72, unit.offsetSize)))
                    0x1b -> Value.Number(indexed(addresses, 0x73, 8))
                    else -> error("DWARF list index cannot be used as a scalar")
                }
            }
            for (reference in listOf(0x47, 0x31)) {
                current.reference(reference)?.let { offset -> lookup(entry(offset))?.let { return it } }
            }
            return null
        }
        return lookup(entry)
    }

    fun name(entry: Entry): String? = (attribute(entry, 0x03) as? Value.Text)?.value

    /** Explicit no-return declarations, keyed by linker identity rather than demangled display names. */
    fun noReturnFunctions(names: Set<String>): Set<String> {
        if (names.isEmpty()) return emptySet()
        val result = mutableSetOf<String>()
        for (unit in units) {
            if (table(unit).values.none { abbreviation -> abbreviation.attributes.any { it.name == 0x87 } }) {
                abbreviationCache.clear()
                continue
            }
            var offset = unit.entries
            while (offset < unit.end) {
                val current = entry(unit, offset)
                offset = current.next
                if (current.tag != 0x2e || (attribute(current, 0x87) as? Value.Number)?.value != 1L) continue
                val linkage = (attribute(current, 0x6e) as? Value.Text)?.value
                    ?: (attribute(current, 0x2007) as? Value.Text)?.value
                val identity = linkage ?: name(current)?.takeIf {
                    (attribute(current, 0x3f) as? Value.Number)?.value == 1L
                }
                if (identity in names) {
                    result += checkNotNull(identity)
                    if (result.containsAll(names)) return result
                }
            }
            abbreviationCache.clear()
        }
        return result
    }

    fun children(parent: Entry): List<Entry> = childEntries(parent).toList()

    /** Stream large LTO compilation units without retaining every child's attributes. */
    fun childEntries(parent: Entry, maximum: Int = 65536): Sequence<Entry> = sequence {
        require(maximum in 1..1048576)
        if (!parent.hasChildren) return@sequence
        var count = 0
        val unit = unit(parent.offset)
        var offset = parent.next
        var depth = 0
        while (offset < unit.end) {
            val child = entry(unit, offset, skipAttributes = depth != 0)
            offset = child.next
            if (child.tag == 0) {
                if (depth == 0) return@sequence
                depth--
            } else {
                if (depth == 0) {
                    require(++count <= maximum) { "DWARF child count exceeds bound" }
                    yield(child)
                }
                if (child.hasChildren) {
                    // A sibling reference skips a complete subtree without materializing it.
                    val sibling = child.reference(0x01)
                    if (sibling != null) {
                        require(sibling >= child.next) { "Backward DWARF sibling reference" }
                        offset = sibling
                    } else {
                        depth++
                        require(depth <= 256) { "DWARF nesting exceeds bound" }
                    }
                }
            }
        }
        error("Unterminated DWARF child list")
    }

    /** Only requested type names are retained, even for large developer executables. */
    fun findTypes(names: Set<String>): Map<String, List<Entry>> {
        val result = names.associateWith { mutableListOf<Entry>() }
        val simpleNames = names.map(::unqualifiedName).toSet()
        val typeTags = setOf(0x02, 0x04, 0x13, 0x16, 0x17, 0x24)
        for (unit in units) {
            // Line-table-only game units can be much larger than all library type units combined.
            if (table(unit).values.none { it.tag in typeTags }) {
                abbreviationCache.clear()
                continue
            }
            val scopes = mutableListOf<String?>()
            var offset = unit.entries
            var rootSeen = false
            while (offset < unit.end) {
                val current = entry(unit, offset)
                offset = current.next
                if (current.tag == 0) {
                    require(scopes.isNotEmpty()) { "Unbalanced DWARF tree" }
                    scopes.removeAt(scopes.lastIndex)
                    continue
                }
                if (!rootSeen) {
                    require(current.tag == 0x11) { "Expected DWARF compilation unit root" }
                    rootSeen = true
                } else require(scopes.isNotEmpty()) { "Multiple DWARF compilation unit roots" }
                val scoped = current.tag in typeTags || current.tag == 0x39
                val name = if (scoped) name(current) else null
                if (current.tag in typeTags && name != null && name in simpleNames) {
                    val qualified = (scopes.filterNotNull() + name).joinToString("::")
                    result[qualified]?.add(current)
                }
                if (current.hasChildren) {
                    require(scopes.size < 256) { "DWARF nesting exceeds bound" }
                    scopes += if (scoped) name ?: "<anonymous>" else if (current.tag == 0x11) null else "<local>"
                }
            }
            require(scopes.isEmpty()) { "Unterminated DWARF compilation unit" }
            // Tables are local to a compilation unit; do not retain every table from a large executable.
            abbreviationCache.clear()
        }
        return result
    }

    private fun unqualifiedName(name: String): String {
        var depth = 0
        var start = 0
        for (index in name.indices) {
            when (name[index]) {
                '<', '(' -> depth++
                '>', ')' -> depth--
                ':' -> if (depth == 0 && index > 0 && name[index - 1] == ':') start = index + 1
            }
        }
        return name.substring(start)
    }

    private fun Long.small(label: String): Int {
        require(this in 0..65535) { "DWARF $label exceeds bound" }
        return toInt()
    }
}
