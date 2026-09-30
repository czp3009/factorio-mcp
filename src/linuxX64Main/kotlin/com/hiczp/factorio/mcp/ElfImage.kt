package com.hiczp.factorio.mcp

/** ELF64 little-endian x86-64 metadata from the selected file, without external symbol tools. */
internal class ElfImage(private val file: BinaryView) {
    private var observedFunctions: MutableSet<Symbol>? = null
    private var observedReadonly: MutableSet<ReadonlyRange>? = null

    data class ReadonlyRange(val address: Long, val size: Long)

    /** Non-executable, non-writable allocated file data. Relocated writable/RELRO pointers need separate checks. */
    fun <T> withReadonlyEvidence(resolve: () -> T): Pair<T, List<ReadonlyRange>> {
        check(observedReadonly == null) { "Readonly evidence collection cannot be nested" }
        val ranges = mutableSetOf<ReadonlyRange>()
        observedReadonly = ranges
        try {
            val result = resolve()
            val merged = mutableListOf<ReadonlyRange>()
            for (range in ranges.sortedBy { it.address }) {
                val previous = merged.lastOrNull()
                if (previous != null && range.address <= previous.address + previous.size) {
                    merged[merged.lastIndex] = previous.copy(
                        size = maxOf(
                            previous.address + previous.size,
                            range.address + range.size
                        ) - previous.address
                    )
                } else merged += range
            }
            require(merged.sumOf { it.size } <= 16 * 1024 * 1024) { "Readonly evidence exceeds byte bound" }
            return result to merged
        } finally {
            observedReadonly = null
        }
    }

    /** Records every function range consulted by a metadata resolver for subsequent live-code comparison. */
    fun <T> withFunctionEvidence(resolve: () -> T): Pair<T, List<Symbol>> {
        check(observedFunctions == null) { "Function evidence collection cannot be nested" }
        val evidence = mutableSetOf<Symbol>()
        observedFunctions = evidence
        try {
            return resolve() to evidence.toList()
        } finally {
            observedFunctions = null
        }
    }

    data class Section(
        val name: String,
        val type: Long,
        val flags: Long,
        val address: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val entrySize: Long,
    )

    data class Segment(
        val type: Long,
        val flags: Long,
        val offset: Long,
        val address: Long,
        val fileSize: Long,
        val memorySize: Long,
        val alignment: Long,
    )

    data class Symbol(val name: String, val address: Long, val size: Long, val type: Int, val section: Int)

    val positionIndependent: Boolean
    val sections: List<Section>
    val segments: List<Segment>

    init {
        file.range(0, 64)
        require(
            file.unsigned(0, 4) == 0x464c457fL && file.unsigned(4, 1) == 2L &&
                    file.unsigned(5, 1) == 1L && file.unsigned(6, 1) == 1L &&
                    file.unsigned(18, 2) == 62L && file.unsigned(20, 4) == 1L &&
                    file.unsigned(52, 2) == 64L
        ) { "Expected a Linux x64 ELF image" }
        val type = file.unsigned(16, 2)
        require(type == 2L || type == 3L) { "Expected an executable or shared ELF image" }
        positionIndependent = type == 3L
        val sectionOffset = file.unsigned(40, 8)
        require(file.unsigned(58, 2) == 64L) { "Unsupported ELF section header size" }
        file.range(sectionOffset, 64)
        val sectionCount = file.unsigned(60, 2).let {
            if (it == 0L) file.unsigned(sectionOffset + 32, 8) else it
        }
        require(sectionCount in 1..65536) { "ELF section count exceeds bound" }
        file.range(sectionOffset, sectionCount * 64)
        val namesIndex = file.unsigned(62, 2).let {
            if (it == 65535L) file.unsigned(sectionOffset + 40, 4) else it
        }
        require(namesIndex in 1 until sectionCount) { "Invalid ELF section names index" }
        val namesHeader = sectionOffset + namesIndex * 64
        require(file.unsigned(namesHeader + 4, 4) == 3L) { "Missing ELF section name strings" }
        val names = file.slice(file.unsigned(namesHeader + 24, 8), file.unsigned(namesHeader + 32, 8))
        sections = List(sectionCount.toInt()) { index ->
            val header = sectionOffset + index * 64
            val section = Section(
                names.string(file.unsigned(header, 4)),
                file.unsigned(header + 4, 4),
                file.unsigned(header + 8, 8),
                file.unsigned(header + 16, 8),
                file.unsigned(header + 24, 8),
                file.unsigned(header + 32, 8),
                file.unsigned(header + 40, 4).also {
                    require(it < sectionCount) { "Invalid ELF linked section" }
                }.toInt(),
                file.unsigned(header + 56, 8),
            )
            require(section.size >= 0 && section.address >= 0) { "ELF section range exceeds bound" }
            if (section.type != 8L && section.type != 0L) file.range(section.offset, section.size)
            section
        }
        val programCount = file.unsigned(56, 2).let {
            if (it == 65535L) file.unsigned(sectionOffset + 44, 4) else it
        }
        require(programCount in 1..65536 && file.unsigned(54, 2) == 56L) { "Invalid ELF program headers" }
        val programOffset = file.unsigned(32, 8)
        file.range(programOffset, programCount * 56)
        segments = List(programCount.toInt()) { index ->
            val header = programOffset + index * 56
            Segment(
                file.unsigned(header, 4), file.unsigned(header + 4, 4),
                file.unsigned(header + 8, 8), file.unsigned(header + 16, 8),
                file.unsigned(header + 32, 8), file.unsigned(header + 40, 8),
                file.unsigned(header + 48, 8),
            ).also {
                file.range(it.offset, it.fileSize)
                require(it.address >= 0 && it.memorySize >= 0 && it.memorySize <= Long.MAX_VALUE - it.address) {
                    "Invalid ELF virtual range"
                }
                if (it.type == 1L) {
                    require(it.fileSize <= it.memorySize) { "ELF load segment is smaller than file data" }
                    require(
                        it.alignment >= 0 && (it.alignment <= 1 ||
                                (it.alignment and (it.alignment - 1) == 0L &&
                                        it.address % it.alignment == it.offset % it.alignment))
                    ) {
                        "Invalid ELF load alignment"
                    }
                }
            }
        }
    }

    fun section(name: String): BinaryView {
        val section = sections.singleOrNull { it.name == name } ?: error("Missing or ambiguous ELF section: $name")
        require(section.type == 1L || section.type == 3L) { "ELF section has no plain data: $name" }
        require(section.flags and 0x800 == 0L) { "Compressed ELF sections are unsupported: $name" }
        return file.slice(section.offset, section.size)
    }

    // Resolution performs many independent symbol/RTTI/ABI lookups. Decode each table once per mapped image;
    // this cache contains metadata only and never survives the owning image's resolution scope.
    private val staticSymbols by lazy { readSymbols(false) }
    private val dynamicSymbols by lazy { readSymbols(true) }
    val unwindFrames: Map<Long, EhFrames.Frame?> by lazy {
        val frames = mutableMapOf<Long, EhFrames.Frame?>()
        var count = 0
        for (frame in EhFrames(this).frames()) {
            require(++count <= 1_000_000) { "ELF unwind frame count exceeds memory bound" }
            // A duplicate start stays ambiguous, including after a third occurrence.
            frames[frame.start] = if (frames.containsKey(frame.start)) null else frame
        }
        frames
    }

    fun symbols(dynamic: Boolean = false): Sequence<Symbol> =
        (if (dynamic) dynamicSymbols else staticSymbols).asSequence()

    private fun readSymbols(dynamic: Boolean): List<Symbol> {
        val tables = sections.filter { it.type == if (dynamic) 11L else 2L }
        require(tables.size == 1) { "Missing or ambiguous ELF symbol table" }
        val table = tables.single()
        require(table.entrySize == 24L && table.size % 24 == 0L && table.size / 24 <= 2_000_000) {
            "Invalid or oversized ELF symbol table"
        }
        val strings = sections[table.link]
        require(strings.type == 3L) { "Invalid ELF symbol strings" }
        val names = file.slice(strings.offset, strings.size)
        val output = mutableListOf<Symbol>()
        var nameCharacters = 0L
        var offset = table.offset
        while (offset < table.offset + table.size) {
            val index = file.unsigned(offset + 6, 2).toInt()
            val nameOffset = file.unsigned(offset, 4)
            if (index != 0 && index < 0xff00 && nameOffset != 0L) {
                require(index < sections.size) { "Invalid ELF symbol section" }
                val address = file.unsigned(offset + 8, 8)
                val size = file.unsigned(offset + 16, 8)
                require(address >= 0 && size >= 0 && size <= Long.MAX_VALUE - address) { "Invalid ELF symbol range" }
                val name = names.string(nameOffset)
                nameCharacters += name.length
                require(nameCharacters <= 64 * 1024 * 1024) { "Decoded ELF symbol names exceed memory bound" }
                output += Symbol(name, address, size, file.unsigned(offset + 4, 1).toInt() and 15, index)
            }
            offset += 24
        }
        return output
    }

    fun symbol(name: String, dynamic: Boolean = false): Symbol =
        symbols(dynamic).filter { it.name == name }.distinct().singleOrNull()
            ?: error("Missing or ambiguous ELF symbol: $name")

    /** Identifies an imported PLT jump using its GOT relocation and dynamic symbol, never a display-name suffix. */
    fun importedFunction(address: Long): String? {
        val section = sections.singleOrNull {
            it.name in setOf(".plt", ".plt.sec", ".plt.got") &&
                    address >= it.address && address - it.address < it.size
        } ?: return null
        require(section.flags and 6L == 6L) { "PLT entry is not in an allocated executable section" }
        val code = X64Instructions(virtualBytes(address, minOf(32, section.size - (address - section.address)), true))
        var jump = code.decode(0)
        if (jump.operation == X64Instructions.Operation.ENDBR) jump = code.decode(jump.size.toLong())
        var resolverIndex: Long? = null
        if (jump.operation == X64Instructions.Operation.MOV && jump.destination == X64Instructions.Register(11, 4)) {
            resolverIndex = (jump.source as? X64Instructions.Immediate)?.value
                ?: error("PLT resolver index is not constant")
            require(resolverIndex >= 0)
            jump = code.decode(jump.offset + jump.size)
        }
        val target = jump.destination as? X64Instructions.Memory ?: error("PLT entry has no GOT operand")
        require(jump.operation == X64Instructions.Operation.JMP && target.relative && target.index == null && target.width == 8)
        val next = address + jump.offset + jump.size
        require(target.displacement >= -next && target.displacement <= Long.MAX_VALUE - next)
        val got = next + target.displacement
        val names = mutableListOf<String>()
        for (relocations in sections.filter { it.type == 4L && it.flags and 2L != 0L }) {
            require(relocations.entrySize == 24L && relocations.size % 24 == 0L && relocations.size / 24 <= 2_000_000)
            val entries = file.slice(relocations.offset, relocations.size).cursor()
            while (entries.remaining > 0) {
                val relocationIndex = entries.position / 24
                val location = entries.unsigned(8)
                val info = entries.unsigned(8)
                val addend = entries.unsigned(8)
                if (location != got) continue
                require(resolverIndex == null || resolverIndex == relocationIndex) { "PLT resolver index disagrees with its relocation" }
                require(
                    info and 0xffffffffL in setOf(
                        6L,
                        7L
                    ) && addend == 0L
                ) { "Unsupported imported function relocation" }
                val table = sections[relocations.link]
                val index = info ushr 32
                require(table.type == 11L && table.entrySize == 24L && table.size % 24 == 0L && index in 1 until table.size / 24)
                val symbol = file.slice(table.offset + index * 24, 24)
                val kind = symbol.unsigned(4, 1).toInt()
                require(kind and 15 == 2 && kind ushr 4 in setOf(1, 2) && symbol.unsigned(6, 2) == 0L)
                val strings = sections[table.link]
                require(strings.type == 3L)
                names += file.slice(strings.offset, strings.size).string(symbol.unsigned(0, 4), 4096)
            }
        }
        return names.singleOrNull()?.takeIf { it.isNotEmpty() }
            ?: error("Missing or ambiguous imported function relocation")
    }

    fun sharedObjectName(): String? {
        val dynamic = sections.filter { it.type == 6L }
        if (dynamic.isEmpty()) return null
        require(dynamic.size == 1) { "Ambiguous ELF dynamic section" }
        val section = dynamic.single()
        require(section.entrySize == 16L && section.size % 16 == 0L && section.size / 16 in 1..65536) {
            "Invalid ELF dynamic table"
        }
        val strings = sections[section.link]
        require(strings.type == 3L) { "Missing ELF dynamic strings" }
        val names = file.slice(strings.offset, strings.size)
        val cursor = file.slice(section.offset, section.size).cursor()
        var name: String? = null
        while (cursor.remaining > 0) {
            val tag = cursor.unsigned(8)
            val value = cursor.unsigned(8)
            if (tag == 0L) return name
            if (tag == 14L) {
                require(name == null) { "Duplicate ELF shared object name" }
                name = names.string(value, 4096)
            }
        }
        error("Unterminated ELF dynamic table")
    }

    fun virtualBytes(address: Long, length: Long, executable: Boolean = false): BinaryView {
        require(address >= 0 && length > 0)
        val segment = segments.singleOrNull {
            it.type == 1L && (!executable || it.flags and 1 != 0L) && address >= it.address &&
                    address - it.address <= it.fileSize && length <= it.fileSize - (address - it.address)
        } ?: error("ELF address is outside a file-backed load segment")
        observedReadonly?.let { evidence ->
            if (!executable && sections.any { section ->
                    section.flags and 7L == 2L && address >= section.address &&
                            length <= section.size && address - section.address <= section.size - length
                }) {
                require(length <= 16 * 1024 * 1024 && evidence.size < 16384) { "Readonly evidence exceeds bounds" }
                evidence += ReadonlyRange(address, length)
            }
        }
        return file.slice(segment.offset + address - segment.address, length)
    }

    fun functionBytes(symbol: Symbol, maximum: Int): BinaryView {
        require(symbol.type == 2 && symbol.size > 0 && maximum > 0) { "ELF symbol has no function range" }
        // Validate the entire range before copying a bounded prologue.
        val bytes =
            virtualBytes(symbol.address, symbol.size, executable = true).slice(0, minOf(symbol.size, maximum.toLong()))
        observedFunctions?.add(symbol)
        return bytes
    }

    fun buildId(): ByteArray {
        val identifiers = mutableListOf<ByteArray>()
        for (section in sections.filter { it.type == 7L }) {
            val cursor = file.slice(section.offset, section.size).cursor()
            while (cursor.remaining > 0) {
                val nameSize = cursor.unsigned(4)
                val dataSize = cursor.unsigned(4)
                val type = cursor.unsigned(4)
                require(nameSize <= 65536 && dataSize <= 65536) { "ELF note exceeds bound" }
                val name = cursor.block(nameSize)
                cursor.skip((4 - nameSize % 4) % 4)
                val data = cursor.block(dataSize)
                cursor.skip((4 - dataSize % 4) % 4)
                if (type == 3L && nameSize == 4L && name.unsigned(0, 4) == 0x00554e47L) {
                    require(dataSize in 1..64) { "ELF build ID exceeds bound" }
                    identifiers += data.bytes(0, dataSize.toInt())
                }
            }
        }
        require(identifiers.size == 1) { "Missing or ambiguous ELF build ID" }
        return identifiers.single()
    }
}
