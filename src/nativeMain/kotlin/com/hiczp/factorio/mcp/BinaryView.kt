package com.hiczp.factorio.mcp

/** A bounded, little-endian view. Subviews share storage with their owning file. */
internal class BinaryView(
    val size: Long,
    private val byteAt: (Long) -> Byte,
    private val copyBytes: ((Long, Int) -> ByteArray)? = null,
) {
    constructor(bytes: ByteArray) : this(
        bytes.size.toLong(), { bytes[it.toInt()] },
        { offset, length -> bytes.copyOfRange(offset.toInt(), offset.toInt() + length) })

    init {
        require(size >= 0)
    }

    fun range(offset: Long, length: Long) {
        require(offset >= 0 && length >= 0 && offset <= size && length <= size - offset) {
            "Invalid binary range: $offset + $length / $size"
        }
    }

    fun unsigned(offset: Long, width: Int): Long {
        require(width in 1..8)
        range(offset, width.toLong())
        var value = 0L
        repeat(width) { value = value or ((byteAt(offset + it).toLong() and 255) shl (8 * it)) }
        return value
    }

    fun slice(offset: Long, length: Long): BinaryView {
        range(offset, length)
        return BinaryView(length, { byteAt(offset + it) }, copyBytes?.let { copy ->
            { start, count -> copy(offset + start, count) }
        })
    }

    fun bytes(offset: Long, length: Int): ByteArray {
        range(offset, length.toLong())
        // Range validation precedes both allocation and the storage callback. Subviews retain their owner's
        // callback so mapping-lifetime checks cannot be bypassed by a previously acquired slice.
        return copyBytes?.invoke(offset, length)?.also { require(it.size == length) }
            ?: ByteArray(length) { byteAt(offset + it) }
    }

    fun string(offset: Long, maximum: Int = 65536): String {
        require(maximum >= 0)
        range(offset, 1)
        var length = 0
        while (length < maximum && length.toLong() < size - offset) {
            if (byteAt(offset + length) == 0.toByte())
                return bytes(offset, length).decodeToString(throwOnInvalidSequence = true)
            length++
        }
        error("Unterminated or oversized binary string")
    }

    fun cursor(offset: Long = 0): BinaryCursor = BinaryCursor(this, offset)
}

internal class BinaryCursor(val view: BinaryView, offset: Long = 0) {
    var position = offset
        private set

    init {
        view.range(offset, 0)
    }

    val remaining: Long
        get() = view.size - position

    fun skip(length: Long) {
        view.range(position, length)
        position += length
    }

    fun unsigned(width: Int): Long = view.unsigned(position, width).also { position += width }

    fun block(length: Long): BinaryView = view.slice(position, length).also { position += length }

    fun string(): String {
        val result = view.string(position)
        // DWARF strings are UTF-8; character count is not their encoded length.
        skip(result.encodeToByteArray().size.toLong() + 1)
        return result
    }

    fun uleb(): Long {
        var result = 0L
        for (index in 0..9) {
            val byte = unsigned(1)
            require(index < 9 || byte and 0xfe == 0L) { "ULEB128 overflow" }
            result = result or ((byte and 127) shl (index * 7))
            if (byte and 128 == 0L) return result
        }
        error("Unterminated ULEB128")
    }

    fun sleb(): Long {
        var result = 0L
        for (index in 0..9) {
            val byte = unsigned(1)
            require(index < 9 || byte == 0L || byte == 127L) { "SLEB128 overflow" }
            val shift = index * 7
            result = result or ((byte and 127) shl shift)
            if (byte and 128 == 0L) {
                if (shift + 7 < 64 && byte and 64 != 0L) result = result or (-1L shl (shift + 7))
                return result
            }
        }
        error("Unterminated SLEB128")
    }
}
