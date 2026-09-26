package com.hiczp.factorio.mcp

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Checked file offsets and RVAs from the selected image, never a game address table. */
internal class PeImage(private val bytes: ByteArray) {
    constructor(path: String) : this(readImage(path))

    private fun range(offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size) {
            "Truncated or invalid PE range"
        }
    }

    private fun u16(offset: Int): Int {
        range(offset, 2)
        return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    }

    private fun u32(offset: Int): Int {
        range(offset, 4)
        val value =
            (0..3).fold(0L) { result, index ->
                result or ((bytes[offset + index].toLong() and 255) shl (8 * index))
            }
        require(value <= Int.MAX_VALUE) { "PE range exceeds supported size" }
        return value.toInt()
    }

    private val pe: Int
    private val optional: Int
    private val sectionTable: Int
    private val sections: Int
    private val directories: Int

    init {
        range(0, 64)
        pe = u32(60)
        range(pe, 24)
        optional = pe + 24
        val optionalSize = u16(pe + 20)
        range(optional, optionalSize)
        require(
            optionalSize >= 112 &&
                    u16(0) == 0x5a4d &&
                    u32(pe) == 0x4550 &&
                    u16(pe + 4) == 0x8664 &&
                    u16(optional) == 0x20b
        ) {
            "Expected a Windows x64 PE image"
        }
        directories = u32(optional + 108)
        require(directories <= (optionalSize - 112) / 8) { "Invalid PE data directory count" }
        sectionTable = optional + optionalSize
        sections = u16(pe + 6)
        require(sections in 1..96) { "Invalid PE section count" }
        range(sectionTable, sections * 40)
    }

    private fun raw(rva: Int, length: Int = 1): Int {
        require(rva > 0 && length > 0) { "Missing PE directory or address" }
        repeat(sections) { index ->
            val section = sectionTable + index * 40
            val start = u32(section + 12)
            if (rva >= start && rva.toLong() + length <= start.toLong() + u32(section + 16)) {
                val offset = u32(section + 20).toLong() + rva - start
                require(offset <= Int.MAX_VALUE) { "Invalid PE section offset" }
                range(offset.toInt(), length)
                return offset.toInt()
            }
        }
        error("Unmapped PE address")
    }

    private fun directory(index: Int): Pair<Int, Int> {
        require(index in 0 until directories) { "Required PE directory is absent" }
        val offset = optional + 112 + index * 8
        return u32(offset) to u32(offset + 4)
    }

    fun verifyLoadedHeaders(read: (Int, Int) -> ByteArray) {
        val headerSize = sectionTable + sections * 40
        val loaded = read(0, headerSize)
        require(loaded.size == headerSize) { "Incomplete loaded PE headers" }
        // Windows may rewrite the PE32+ preferred ImageBase after ASLR relocation.
        bytes.copyInto(loaded, optional + 24, optional + 24, optional + 32)
        require(loaded.contentEquals(bytes.copyOfRange(0, headerSize))) {
            "Loaded image differs from the file on disk; restart Factorio before attaching"
        }
    }

    fun functionEnd(rva: Long): Long {
        val (start, size) = directory(3)
        require(size % 12 == 0) { "Invalid PE unwind directory size" }
        val offset = raw(start, size)
        for (entry in offset until offset + size step 12) if (u32(entry).toLong() == rva) {
            val end = u32(entry + 4).toLong()
            require(end > rva) { "Invalid PE unwind range" }
            return end
        }
        error("Required function has no unwind range")
    }

    fun debugIdentity(): Pair<ByteArray, Int> {
        val (start, size) = directory(6)
        require(size % 28 == 0) { "Invalid PE debug directory size" }
        val offset = raw(start, size)
        for (entry in offset until offset + size step 28) if (u32(entry + 12) == 2) {
            val length = u32(entry + 16)
            require(length >= 24) { "Truncated CodeView record" }
            val record = raw(u32(entry + 20), length)
            if (u32(record) == 0x53445352)
                return bytes.copyOfRange(record + 4, record + 20) to u32(record + 20)
        }
        error("Developer PDB identity is absent")
    }

    fun export(name: String): Long {
        val (start, size) = directory(0)
        require(size >= 40) { "Truncated PE export directory" }
        val table = raw(start, size)
        val count = u32(table + 24)
        val functionCount = u32(table + 20)
        require(count in 1..bytes.size / 4 && functionCount in 1..bytes.size / 4) {
            "Invalid PE export counts"
        }
        val names = raw(u32(table + 32), count * 4)
        val ordinals = raw(u32(table + 36), count * 2)
        val functions = raw(u32(table + 28), functionCount * 4)
        repeat(count) { index ->
            val string = raw(u32(names + index * 4))
            // Only compare the requested name; never scan an unbounded malformed export string.
            if (
                string.toLong() + name.length < bytes.size &&
                bytes[string + name.length] == 0.toByte() &&
                bytes.decodeToString(string, string + name.length) == name
            ) {
                val ordinal = u16(ordinals + index * 2)
                require(ordinal < functionCount) { "Invalid PE export ordinal" }
                val rva = u32(functions + 4 * ordinal)
                require(rva.toLong() !in start.toLong() until start.toLong() + size) {
                    "Forwarded resident exports are unsupported"
                }
                raw(rva)
                return rva.toLong()
            }
        }
        error("Missing resident export: $name")
    }
}

private fun readImage(path: String): ByteArray {
    val file = Path(path)
    val size = SystemFileSystem.metadataOrNull(file)?.size ?: error("PE image is absent")
    require(size in 64..536870912) { "PE file size exceeds supported bounds" }
    return SystemFileSystem.source(file).buffered().use { it.readByteArray() }
}
