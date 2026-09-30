package com.hiczp.factorio.mcp

internal data class ProcMapping(
    val start: Long,
    val end: Long,
    val permissions: String,
    val offset: Long,
    val deviceMajor: Long,
    val deviceMinor: Long,
    val inode: Long,
    val path: String?,
) {
    val readable: Boolean
        get() = permissions[0] == 'r'

    val writable: Boolean
        get() = permissions[1] == 'w'

    val executable: Boolean
        get() = permissions[2] == 'x'

    companion object {
        private val line =
            Regex("^([0-9a-f]+)-([0-9a-f]+) ([r-][w-][x-][ps]) ([0-9a-f]+) ([0-9a-f]+):([0-9a-f]+) +([0-9]+)(?: +(.*))?$")

        fun parse(text: String): List<ProcMapping> {
            val result = text.lineSequence().filter(String::isNotEmpty).map { text ->
                val match = line.matchEntire(text) ?: error("Invalid /proc maps record")
                val values = match.groupValues
                fun number(index: Int, radix: Int = 16) = values[index].toULong(radix).also {
                    require(it <= Long.MAX_VALUE.toULong()) { "Process mapping exceeds user address range" }
                }.toLong()
                // The x86-64 legacy vsyscall page is outside user-readable positive addresses.
                if (values[1].toULong(16) > Long.MAX_VALUE.toULong()) {
                    require(values[8] == "[vsyscall]") { "Unexpected kernel address in process maps" }
                    null
                } else ProcMapping(
                    number(1), number(2), values[3], number(4), number(5), number(6), number(7, 10),
                    values[8].takeIf(String::isNotEmpty)
                ).also {
                    require(it.end > it.start) { "Empty or inverted process mapping" }
                }
            }.filterNotNull().toList()
            require(result.size <= 65536) { "Process mapping count exceeds bound" }
            require(result.zipWithNext().all { (first, second) -> first.end <= second.start }) {
                "Overlapping or unordered process mappings"
            }
            return result
        }
    }
}

/** Mapping identities, not path strings, select the loaded file, including renamed executables. */
internal fun ElfImage.loadBias(mappings: List<ProcMapping>, pageSize: Long): Long {
    require(pageSize > 0 && pageSize and (pageSize - 1) == 0L)
    fun page(value: Long) = value and -pageSize
    val loads = segments.filter { it.type == 1L && it.fileSize > 0 }
    require(loads.isNotEmpty()) { "No file-backed ELF load segments" }
    val first = loads.first()
    val candidates = mappings.filter { it.offset == page(first.offset) }.map { it.start - page(first.address) }
    val valid = candidates.filter { bias ->
        bias >= 0 && (positionIndependent || bias == 0L) && loads.all { segment ->
            segment.address <= Long.MAX_VALUE - bias && mappings.any { mapping ->
                val address = bias + segment.address
                address >= mapping.start && address < mapping.end &&
                        segment.offset >= mapping.offset &&
                        address - mapping.start == segment.offset - mapping.offset
            }
        }
    }.distinct()
    require(valid.size == 1) { "Cannot establish a unique ELF load bias from process maps" }
    return valid.single()
}
