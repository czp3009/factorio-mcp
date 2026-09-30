package com.hiczp.factorio.mcp

/** Inline identities/ranges inside an exact ELF/unwind function. These do not establish argument or getter ABI. */
internal class DwarfInlines(private val image: ElfImage) {
    data class Instance(val entry: Long, val name: String, val origin: Long, val ranges: List<DwarfRanges.Range>)

    private val info = DwarfInfo(image)
    private val ranges = DwarfRanges(image, info)
    private val unitRanges = mutableMapOf<Long, List<DwarfRanges.Range>>()
    private val unitDefinitions = mutableMapOf<Long, Map<DwarfRanges.Range, List<Long>>>()

    private fun indexDefinitions(root: DwarfInfo.Entry): Map<DwarfRanges.Range, List<Long>> =
        unitDefinitions.getOrPut(root.offset) {
            val result = mutableMapOf<DwarfRanges.Range, MutableList<Long>>()
            var visited = 0
            fun collect(parent: DwarfInfo.Entry, depth: Int) {
                require(depth <= 128)
                for (child in info.childEntries(parent, 1048576)) {
                    require(++visited <= 1048576) { "DWARF function search exceeds bound" }
                    if (child.tag == 0x2e) {
                        val body = ranges.ranges(child)
                        if (body.isNotEmpty()) {
                            if (body.size == 1) result.getOrPut(body.single()) { mutableListOf() }.add(child.offset)
                            continue
                        }
                    }
                    if (child.hasChildren) collect(child, depth + 1)
                }
            }
            collect(root, 0)
            result
        }

    fun find(function: ElfImage.Symbol, ownerName: String, names: Set<String>): List<Instance> {
        require(names.isNotEmpty() && names.size <= 64 && ownerName.isNotEmpty())
        EhFrames(image).function(function)
        require(function.address >= 0 && function.size > 0 && function.size <= Long.MAX_VALUE - function.address)
        val bound = DwarfRanges.Range(function.address, function.address + function.size)
        val definitions = mutableListOf<DwarfInfo.Entry>()
        for (unit in info.units) {
            val root = info.root(unit)
            require(root.tag == 0x11)
            if (unitRanges.getOrPut(unit.start) { ranges.ranges(root) }.any { it.contains(bound) }) {
                definitions += indexDefinitions(root)[bound].orEmpty().map(info::entry)
            }
            info.releaseAbbreviations()
        }
        val definition = definitions.singleOrNull() ?: error("ELF function has no unique complete DWARF definition")
        require(info.name(definition) == ownerName) { "DWARF function name does not match its selected owner" }
        val result = mutableListOf<Instance>()
        var visited = 0
        fun inlines(parent: DwarfInfo.Entry, containing: List<DwarfRanges.Range>, depth: Int) {
            require(depth <= 128)
            for (child in info.children(parent)) {
                require(++visited <= 65536) { "DWARF inline tree exceeds bound" }
                require(child.tag != 0x2e || ranges.ranges(child).isEmpty()) {
                    "Unexpected concrete function nested inside selected function"
                }
                val body = ranges.ranges(child)
                require(body.all { span -> containing.any { it.contains(span) } }) {
                    "DWARF inline/lexical range escapes its enclosing scope"
                }
                val name = if (child.tag == 0x1d) info.name(child) else null
                if (name in names && body.isNotEmpty()) {
                    val origin = child.reference(0x31)?.let(info::entry) ?: error("Inline range has no abstract origin")
                    require(origin.tag == 0x2e && info.name(origin) == name)
                    result += Instance(child.offset, checkNotNull(name), origin.offset, body)
                    require(result.size <= 4096)
                }
                if (child.hasChildren) inlines(child, body.ifEmpty { containing }, depth + 1)
            }
        }
        inlines(definition, listOf(bound), 0)
        require(names.all { name -> result.any { it.name == name } }) { "Requested inline accessor lacks a code range" }
        return result
    }
}
