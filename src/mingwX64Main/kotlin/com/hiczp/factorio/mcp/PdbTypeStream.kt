package com.hiczp.factorio.mcp

/**
 * Bounded MSF 7 stream reader. Constants describe the file format, never a game layout. Format
 * reference: https://llvm.org/docs/PDB/MsfFile.html
 */
internal class PdbStreams(private val fileSize: Long, private val read: (Long, Int) -> ByteArray) {
    private val pageSize: Int
    private val directory: PdbBytes
    private val sizes: IntArray
    private val pages: IntArray

    init {
        val header = PdbBytes(block(0, 56))
        require(
            header.bytes
                .copyOfRange(0, 32)
                .contentEquals(
                    "Microsoft C/C++ MSF 7.00\r\n\u001aDS\u0000\u0000\u0000".encodeToByteArray()
                )
        ) {
            "Unsupported PDB container"
        }
        pageSize = header.size(32)
        require(pageSize in 512..65536 && pageSize and (pageSize - 1) == 0)
        require(header.size(40).toLong() * pageSize == fileSize) { "Invalid PDB page count" }
        val directorySize = header.size(44)
        require(directorySize in 4..16 * 1024 * 1024)
        val pageCount = pageCount(directorySize)
        require(pageCount * 4 <= pageSize) { "Unsupported PDB directory map" }
        val map = PdbBytes(block(header.size(52).toLong() * pageSize, pageCount * 4))
        directory = PdbBytes(copyPages(directorySize) { map.size(it * 4) })
        val count = directory.size(0)
        require(count in 3..65536)
        directory.range(4, count * 4)
        sizes = IntArray(count) { directory.u32(4 + it * 4).toInt() }
        var offset = 4 + count * 4
        pages =
            IntArray(count) { index ->
                val first = offset
                val size = sizes[index]
                require(size >= 0 || size == -1) { "PDB stream exceeds supported size" }
                offset += pageCount(size.coerceAtLeast(0)) * 4
                directory.range(first, offset - first)
                first
            }
        require(offset == directory.bytes.size) { "Unexpected PDB stream directory data" }
    }

    private fun block(offset: Long, size: Int): ByteArray {
        require(offset >= 0 && size >= 0 && offset <= fileSize - size) { "Invalid PDB file range" }
        return read(offset, size).also { require(it.size == size) { "Truncated PDB read" } }
    }

    private fun pageCount(size: Int) = ((size.toLong() + pageSize - 1) / pageSize).toInt()

    private fun copyPages(size: Int, page: (Int) -> Int): ByteArray {
        val output = ByteArray(size)
        repeat(pageCount(size)) { index ->
            val offset = index * pageSize
            val count = minOf(pageSize, size - offset)
            block(page(index).toLong() * pageSize, count).copyInto(output, offset)
        }
        return output
    }

    fun stream(index: Int, limit: Int): ByteArray {
        require(index in sizes.indices && sizes[index] in 0..limit) {
            "Unavailable or oversized PDB stream"
        }
        return copyPages(sizes[index]) { directory.size(pages[index] + it * 4) }
    }

    fun verifyIdentity(guid: ByteArray, age: Int) {
        val info = PdbBytes(stream(1, 16 * 1024 * 1024))
        info.range(0, 28)
        require(
            guid.size == 16 &&
                    info.size(8) == age &&
                    info.bytes.copyOfRange(12, 28).contentEquals(guid)
        ) {
            "PDB stream identity does not match the executable"
        }
    }
}

internal class PdbBytes(val bytes: ByteArray) {
    fun range(offset: Int, size: Int) {
        require(offset >= 0 && size >= 0 && offset.toLong() + size <= bytes.size) {
            "Invalid PDB record range"
        }
    }

    fun u16(offset: Int): Int {
        range(offset, 2)
        return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    }

    fun u32(offset: Int): UInt {
        range(offset, 4)
        return (bytes[offset].toUInt() and 255u) or
                ((bytes[offset + 1].toUInt() and 255u) shl 8) or
                ((bytes[offset + 2].toUInt() and 255u) shl 16) or
                ((bytes[offset + 3].toUInt() and 255u) shl 24)
    }

    fun size(offset: Int): Int = u32(offset).also { require(it <= Int.MAX_VALUE.toUInt()) }.toInt()

    fun name(offset: Int): Pair<String, Int> {
        range(offset, 1)
        var end = offset
        while (end < bytes.size && bytes[end] != 0.toByte() && end - offset <= 4096) end++
        require(end < bytes.size && end - offset <= 4096) { "Unterminated PDB name" }
        return bytes.decodeToString(offset, end, throwOnInvalidSequence = true) to (end + 1)
    }

    fun numericEnd(offset: Int): Int {
        val tag = u16(offset)
        val width =
            when (tag) {
                in 0..0x7fff -> 0
                0x8000 -> 1
                0x8001,
                0x8002 -> 2

                0x8003,
                0x8004 -> 4

                0x8009,
                0x800a -> 8

                else -> error("Unsupported CodeView integer leaf")
            }
        range(offset, 2 + width)
        return offset + 2 + width
    }
}

/**
 * Reads introducing virtual methods from CodeView records, independently of DbgHelp's synthetic
 * type IDs. Record definitions:
 * https://github.com/microsoft/microsoft-pdb/blob/master/include/cvinfo.h
 */
internal class PdbTypeStream(bytes: ByteArray) {
    private val data = PdbBytes(bytes)
    private val begin: Int
    private val offsets: IntArray

    init {
        require(data.size(0) == 20040203) { "Unsupported PDB type stream version" }
        val header = data.size(4)
        require(header >= 56 && header <= bytes.size)
        begin = data.size(8)
        val end = data.size(12)
        require(begin >= 0x1000 && end >= begin && end - begin <= 4_000_000)
        val size = data.size(16)
        data.range(header, size)
        var offset = header
        offsets =
            IntArray(end - begin) {
                val length = data.u16(offset)
                require(length >= 2 && offset.toLong() + length + 2 <= header.toLong() + size)
                val record = offset + 2
                offset += length + 2
                record
            }
        require(offset == header + size) { "PDB type record count mismatch" }
    }

    private fun record(index: Int): PdbBytes {
        require(index - begin in offsets.indices) { "Invalid CodeView type reference" }
        val offset = offsets[index - begin]
        return PdbBytes(data.bytes.copyOfRange(offset, offset + data.u16(offset - 2)))
    }

    private fun className(record: PdbBytes): String {
        require(record.u16(0) in listOf(0x1504, 0x1505)) { "Expected a CodeView class" }
        return record.name(record.numericEnd(18)).first
    }

    data class VirtualMethod(val offset: UInt, val returnType: UInt)

    fun methods(
        owner: String,
        signatures: Map<String, UInt>,
        pointerSignatures: Map<String, String> = emptyMap(),
        aggregateSignatures: Map<String, String> = emptyMap(),
        primaryBase: String? = null,
    ): Map<String, VirtualMethod> {
        val requested = signatures.keys + pointerSignatures.keys + aggregateSignatures.keys
        require(
            requested.isNotEmpty() &&
                    requested.size ==
                    signatures.size + pointerSignatures.size + aggregateSignatures.size
        )
        var definition: PdbBytes? = null
        val wantedName = owner.encodeToByteArray()
        offsets.forEach { offset ->
            val leaf = data.u16(offset)
            if ((leaf == 0x1504 || leaf == 0x1505) && data.u16(offset + 4) and 0x80 == 0) {
                val end = offset + data.u16(offset - 2)
                require(end - offset >= 20)
                val name = data.numericEnd(offset + 18)
                require(name < end)
                // Avoid allocating strings and record copies for millions of nonmatches.
                var matches =
                    name.toLong() + wantedName.size < end &&
                            data.bytes[name + wantedName.size] == 0.toByte()
                var index = 0
                while (matches && index < wantedName.size) {
                    matches = data.bytes[name + index] == wantedName[index]
                    index++
                }
                if (matches) {
                    require(definition == null) { "Ambiguous CodeView class: $owner" }
                    definition = PdbBytes(data.bytes.copyOfRange(offset, end))
                }
            }
        }
        val type = requireNotNull(definition) { "Missing CodeView class: $owner" }
        val shape = record(type.size(14))
        require(shape.u16(0) == 0x000a && shape.u16(2) in 1..256) {
            "Unsupported virtual table shape"
        }
        shape.range(4, (shape.u16(2) + 1) / 2)
        val fields = record(type.size(6))
        require(fields.u16(0) == 0x1203)
        val result = mutableMapOf<String, VirtualMethod>()
        var offset = 2
        var pointers = 0
        var bases = 0
        while (offset < fields.bytes.size) {
            val first = fields.bytes[offset].toInt() and 255
            if (first >= 0xf0) {
                val padding = first - 0xf0
                require(padding in 1..3)
                fields.range(offset, padding)
                repeat(padding) {
                    require((fields.bytes[offset + it].toInt() and 255) == first - it)
                }
                offset += padding
                continue
            }
            when (fields.u16(offset)) {
                0x1400 -> { // LF_BCLASS; only an explicitly selected primary interface is
                    // supported.
                    require(
                        primaryBase != null &&
                                className(record(fields.size(offset + 4))) == primaryBase
                    )
                    require(fields.u16(offset + 8) == 0) { "Nonzero interface base offset" }
                    bases++
                    offset += 10
                }

                0x1409 -> {
                    fields.range(offset, 8)
                    pointers++
                    offset += 8
                }

                0x1510,
                0x150e,
                0x150f -> offset = fields.name(offset + 8).second

                0x150d -> offset = fields.name(fields.numericEnd(offset + 8)).second
                0x1511 -> {
                    val attributes = fields.u16(offset + 2)
                    val introducing = (attributes shr 2 and 7) in listOf(4, 6)
                    val (name, next) = fields.name(offset + if (introducing) 12 else 8)
                    if (introducing && name in requested) {
                        val slot = fields.size(offset + 8)
                        require(slot % 8 == 0 && slot / 8 < shape.u16(2)) {
                            "Invalid virtual method offset"
                        }
                        val function = record(fields.size(offset + 4))
                        require(function.u16(0) == 0x1009)
                        if (name in signatures) {
                            require(function.u32(2) == signatures.getValue(name)) {
                                "Unexpected virtual return type: $owner::$name"
                            }
                        } else if (name in aggregateSignatures) {
                            // This checks the declared return, not its optimized ABI. The caller
                            // must
                            // separately establish hidden return storage for this exact adapter.
                            require(
                                className(record(function.size(2))) ==
                                        aggregateSignatures.getValue(name)
                            ) {
                                "Unexpected virtual aggregate return: $owner::$name"
                            }
                        } else {
                            val pointer = record(function.size(2))
                            require(pointer.u16(0) == 0x1002)
                            val flags = pointer.u32(6)
                            require(
                                flags and 31u == 12u &&
                                        flags shr 5 and 7u == 0u &&
                                        flags shr 13 and 63u == 8u
                            ) {
                                "Expected a 64-bit plain pointer return"
                            }
                            val qualified = record(pointer.size(2))
                            require(qualified.u16(0) == 0x1001 && qualified.u16(6) == 1) {
                                "Expected a pointer to a const class"
                            }
                            require(
                                className(record(qualified.size(2))) ==
                                        pointerSignatures.getValue(name)
                            ) {
                                "Unexpected virtual pointer target: $owner::$name"
                            }
                        }
                        require(className(record(function.size(6))) == owner)
                        // Win64 no-argument instance method, without an additional this adjustment.
                        require(
                            function.u16(14) == (if (name in aggregateSignatures) 0x100 else 0) &&
                                    function.u16(16) == 0 &&
                                    function.u32(22) == 0u
                        ) {
                            "Unsupported virtual method signature: $owner::$name"
                        }
                        val arguments = record(function.size(18))
                        require(arguments.u16(0) == 0x1201 && arguments.size(2) == 0)
                        val pointer = record(function.size(10))
                        val flags = pointer.u32(6)
                        require(
                            pointer.u16(0) == 0x1002 &&
                                    flags and 31u == 12u &&
                                    flags shr 5 and 7u == 0u &&
                                    flags shr 13 and 63u == 8u
                        )
                        val qualified = record(pointer.size(2))
                        require(qualified.u16(0) == 0x1001 && qualified.u16(6) == 1)
                        require(className(record(qualified.size(2))) == owner)
                        require(
                            result.put(name, VirtualMethod(slot.toUInt(), function.u32(2))) == null
                        )
                    }
                    offset = next
                }

                else -> error("Unsupported CodeView interface field: $owner")
            }
        }
        require(
            (if (primaryBase == null) pointers == 1 && bases == 0
            else pointers == 0 && bases == 1) &&
                    result.keys == requested &&
                    result.values.map { it.offset }.distinct().size == result.size
        )
        return result
    }
}
