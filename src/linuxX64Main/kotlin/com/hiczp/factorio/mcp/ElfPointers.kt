package com.hiczp.factorio.mcp

/** Resolves local pointer words with their ELF relocations, without treating unrelocated PIE data as addresses. */
internal class ElfPointers(private val image: ElfImage) {
    private data class Relocation(val target: Long, val info: Long, val addend: Long)

    // Scoped to this selected-file reader. Keep duplicate records for words() to reject, never collapse them.
    private val relocations: List<Relocation> by lazy {
        buildList {
            for (section in image.sections.filter { it.flags and 2 != 0L && it.type in setOf(4L, 9L, 19L) }) {
                require(section.type == 4L) { "ELF REL/RELR pointer relocations are unsupported" }
                require(section.entrySize == 24L && section.size % 24 == 0L && section.size / 24 <= 2_000_000) {
                    "Invalid or oversized ELF RELA table"
                }
                if (section.size == 0L) continue
                val entries = image.virtualBytes(section.address, section.size).cursor()
                while (entries.remaining > 0) {
                    val target = entries.unsigned(8)
                    val info = entries.unsigned(8)
                    val addend = entries.unsigned(8)
                    require(target >= 0) { "Invalid ELF relocation target" }
                    require(size < 2_000_000) { "ELF relocation count exceeds bound" }
                    add(Relocation(target, info, addend))
                }
            }
        }.sortedBy { it.target }
    }

    /** Candidate locations only. Call words() before trusting a location, including duplicate/overlap checks. */
    fun relativeReferences(value: Long): Set<Long> {
        require(value > 0)
        val result = relocations.filter { it.info == 8L && it.addend == value }.map { it.target }.toSet()
        require(result.size <= 65536)
        return result
    }

    fun words(address: Long, count: Int): List<Word> {
        require(count in 1..8192 && address >= 0 && address % 8 == 0L && address <= Long.MAX_VALUE - count * 8L) {
            "Invalid ELF pointer table range"
        }
        val bytes = image.virtualBytes(address, count * 8L)
        val end = address + count * 8L
        val selected = mutableMapOf<Long, Long>()
        var low = 0
        var high = relocations.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (relocations[middle].target < address - 7) low = middle + 1 else high = middle
        }
        var index = low
        while (index < relocations.size && relocations[index].target < end) {
            val record = relocations[index++]
            require(record.target >= address && record.target % 8 == 0L && record.target <= end - 8) {
                "ELF relocation partially overlaps pointer table"
            }
            require(record.info == 8L && record.addend >= 0) { "Pointer needs a local R_X86_64_RELATIVE relocation" }
            require(selected.put(record.target, record.addend) == null) { "Duplicate ELF pointer relocation" }
        }
        return List(count) { word ->
            Word(bytes.unsigned(word * 8L, 8), selected[address + word * 8L], image.positionIndependent)
        }
    }

    data class Word(val raw: Long, val relocation: Long?, val positionIndependent: Boolean) {
        fun scalar(): Long {
            require(relocation == null) { "ELF scalar has a pointer relocation" }
            return raw
        }

        fun pointer(): Long {
            if (relocation != null) return relocation
            require(!positionIndependent || raw == 0L) { "ELF PIE pointer lacks a relocation" }
            require(raw >= 0) { "ELF pointer is outside the supported address range" }
            return raw
        }
    }
}
