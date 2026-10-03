package com.hiczp.factorio.mcp

/** Exact source rows of one selected CU; source identity alone does not prove layout or ABI. */
internal class DwarfSourceLines(
    private val section: BinaryView,
    private val strings: BinaryView? = null,
    private val lineStrings: BinaryView? = null,
) {
    data class Source(
        val path: String,
        val line: Long,
        val column: Long,
        val checksum: List<Byte>? = null,
    )

    private data class File(val name: String, val directory: Long, val checksum: List<Byte>? = null)

    fun rows(offset: Long, sites: Set<Long>, compilationDirectory: String): Map<Long, Source> {
        require(offset >= 0 && sites.isNotEmpty() && sites.size <= 4096 && sites.all { it >= 0 })
        val input = section.cursor(offset)
        var length = input.unsigned(4)
        val width = if (length == 0xffffffffL) 8 else 4
        if (width == 8) length = input.unsigned(8) else require(length < 0xfffffff0L)
        require(length > 0 && length <= input.remaining)
        val unit = input.block(length).cursor()
        val version = unit.unsigned(2)
        require(version in 4..5)
        if (version == 5L) require(unit.unsigned(1) == 8L && unit.unsigned(1) == 0L)
        val headerLength = unit.unsigned(width)
        require(headerLength in 1..16 * 1024 * 1024 && headerLength <= unit.remaining)
        val header = unit.block(headerLength).cursor()
        val minimum = header.unsigned(1)
        require(minimum in 1..16 && header.unsigned(1) == 1L) { "VLIW source rows are unsupported" }
        require(header.unsigned(1) in 0..1)
        val lineBase = header.unsigned(1).toByte().toLong()
        val lineRange = header.unsigned(1)
        val opcodeBase = header.unsigned(1)
        require(lineRange > 0 && opcodeBase in 13..255)
        val operands = List((opcodeBase - 1).toInt()) { header.unsigned(1) }
        require(operands.take(12) == listOf(0L, 1L, 1L, 1L, 1L, 0L, 0L, 0L, 1L, 0L, 0L, 1L))
        val directories = mutableListOf<String>()
        val files = mutableListOf<File>()
        fun oldFile(cursor: BinaryCursor, name: String): File {
            val directory = cursor.uleb()
            require(directory >= 0)
            require(cursor.uleb() >= 0 && cursor.uleb() >= 0)
            return File(name, directory)
        }
        if (version == 4L) {
            directories += compilationDirectory
            while (true) {
                val directory = header.string()
                if (directory.isEmpty()) break
                require(directories.size < 65536)
                directories += directory
            }
            // Version 4 file indices start at one; zero never denotes a source file.
            files += File("", 0)
            while (true) {
                val name = header.string()
                if (name.isEmpty()) break
                require(files.size < 65536)
                files += oldFile(header, name)
            }
        } else {
            fun entries(): List<Map<Long, Any>> {
                val count = header.unsigned(1).toInt()
                require(count in 0..8)
                val formats = List(count) { header.uleb() to header.uleb() }
                require(formats.map { it.first }.distinct().size == count)
                val amount = header.uleb()
                require(amount in 0..65536)
                require(amount == 0L || formats.any { it.first == 1L })
                return List(amount.toInt()) {
                    formats.associate { (kind, form) ->
                        require(kind in 1..5)
                        val value: Any =
                            when (form) {
                                0x08L -> header.string()
                                0x0eL -> checkNotNull(strings).string(header.unsigned(width))
                                0x1fL -> checkNotNull(lineStrings).string(header.unsigned(width))
                                0x0bL -> header.unsigned(1)
                                0x05L -> header.unsigned(2)
                                0x06L -> header.unsigned(4)
                                0x07L -> header.unsigned(8)
                                0x0fL -> header.uleb()
                                0x1eL -> header.block(16).bytes(0, 16).toList()
                                else -> error("Unsupported source table form: $form")
                            }
                        require(
                            if (kind == 1L) value is String
                            else if (kind == 5L) value is List<*> else value is Long && value >= 0
                        )
                        kind to value
                    }
                }
            }
            directories += entries().map { it.getValue(1) as String }
            files +=
                entries().map {
                    File(
                        it.getValue(1) as String,
                        it[2] as? Long ?: 0L,
                        (it[5] as? List<*>)?.map { byte -> byte as Byte },
                    )
                }
        }
        require(header.remaining == 0L && files.size in 1..65536)
        fun path(file: Long): String {
            require(file in 0 until files.size.toLong() && (version == 5L || file != 0L))
            val selected = files[file.toInt()]
            require(selected.name.isNotEmpty())
            if (selected.name.startsWith('/')) return selected.name
            require(selected.directory in 0 until directories.size.toLong())
            fun join(parent: String, child: String) =
                if (parent.isEmpty()) child else "${parent.trimEnd('/')}/$child"
            val directory = directories[selected.directory.toInt()]
            val root =
                if (directory.startsWith('/') || version == 4L && selected.directory == 0L)
                    directory
                else join(compilationDirectory, directory)
            return join(root, selected.name)
        }
        var address: Long? = null
        var file = 1L
        var line = 1L
        var column = 0L
        var work = 0
        val selected = mutableMapOf<Long, MutableSet<Source>>()
        fun advance(operations: Long) {
            val current = checkNotNull(address)
            require(operations >= 0 && operations <= (Long.MAX_VALUE - current) / minimum)
            address = current + operations * minimum
        }
        fun advanceLine(delta: Long) {
            require(
                delta >= 0 && line <= Long.MAX_VALUE - delta ||
                    delta != Long.MIN_VALUE && delta < 0 && line >= -delta
            )
            line += delta
        }
        fun emit() {
            val current = checkNotNull(address)
            if (current in sites) {
                require(line > 0 && column >= 0)
                selected.getOrPut(current) { mutableSetOf() } +=
                    Source(path(file), line, column, files[file.toInt()].checksum)
            }
        }
        while (unit.remaining > 0) {
            require(++work <= 64 * 1024 * 1024)
            val opcode = unit.unsigned(1)
            when {
                opcode == 0L -> {
                    val size = unit.uleb()
                    require(size > 0 && size <= unit.remaining)
                    val extended = unit.block(size).cursor()
                    when (val operation = extended.unsigned(1)) {
                        1L -> {
                            require(extended.remaining == 0L && address != null)
                            address = null
                            file = 1
                            line = 1
                            column = 0
                        }
                        2L -> {
                            require(extended.remaining == 8L)
                            val next = extended.unsigned(8)
                            require(next >= 0 && (address == null || next >= checkNotNull(address)))
                            address = next
                        }
                        3L -> {
                            require(version == 4L && files.size < 65536)
                            val name = extended.string()
                            require(name.isNotEmpty())
                            files += oldFile(extended, name)
                            require(extended.remaining == 0L)
                        }
                        4L -> require(extended.uleb() >= 0 && extended.remaining == 0L)
                        else -> error("Unsupported extended source opcode: $operation")
                    }
                }
                opcode >= opcodeBase -> {
                    val adjusted = opcode - opcodeBase
                    advance(adjusted / lineRange)
                    advanceLine(lineBase + adjusted % lineRange)
                    emit()
                }
                else ->
                    when (opcode) {
                        1L -> emit()
                        2L -> advance(unit.uleb())
                        3L -> advanceLine(unit.sleb())
                        4L -> file = unit.uleb().also { require(it >= 0) }
                        5L -> column = unit.uleb().also { require(it >= 0) }
                        6L,
                        7L,
                        10L,
                        11L -> Unit
                        8L -> advance((255 - opcodeBase) / lineRange)
                        9L -> {
                            val current = checkNotNull(address)
                            val delta = unit.unsigned(2)
                            require(delta <= Long.MAX_VALUE - current)
                            address = current + delta
                        }
                        12L -> require(unit.uleb() >= 0)
                        else -> error("Unsupported standard source opcode: $opcode")
                    }
            }
        }
        require(address == null && selected.keys == sites) {
            "Selected instruction lacks an exact, terminated source row"
        }
        return selected.mapValues { (_, sources) ->
            sources.singleOrNull() ?: error("Selected instruction has ambiguous source coordinates")
        }
    }
}
