package com.hiczp.factorio.mcp

/** Bounded .eh_frame ranges and CFI payloads; reading a range does not authorize relocating it. */
internal class EhFrames(private val image: ElfImage) {
    data class Common(
        val offset: Long,
        val codeAlignment: Long,
        val dataAlignment: Long,
        val returnRegister: Long,
        val pointerEncoding: Int,
        val augmented: Boolean,
        val instructions: BinaryView,
    )

    data class Frame(val start: Long, val end: Long, val common: Common, val instructions: BinaryView)

    private val section = image.sections.singleOrNull { it.name == ".eh_frame" }
        ?: error("Missing or ambiguous ELF unwind section")
    private val bytes = image.section(".eh_frame")

    fun frames(): Sequence<Frame> = sequence {
        val common = mutableMapOf<Long, Common>()
        val cursor = bytes.cursor()
        while (cursor.remaining > 0) {
            val offset = cursor.position
            var length = cursor.unsigned(4)
            if (length == 0L) {
                while (cursor.remaining > 0) require(cursor.unsigned(1) == 0L) { "Nonzero data after unwind terminator" }
                break
            }
            if (length == 0xffffffffL) length = cursor.unsigned(8)
            else require(length < 0xfffffff0L) { "Reserved unwind record length" }
            require(length >= 4 && length <= cursor.remaining) { "Invalid unwind record length" }
            val recordStart = cursor.position
            val record = cursor.block(length).cursor()
            // .eh_frame CIE pointers stay four bytes even in the extended-length format.
            val pointer = record.unsigned(4)
            if (pointer == 0L) {
                require(common.size < 65536) { "Unwind CIE count exceeds bound" }
                common[offset] = readCommon(offset, recordStart, record)
            } else {
                require(pointer <= recordStart) { "Invalid unwind CIE reference" }
                val cie = common[recordStart - pointer] ?: error("Missing preceding unwind CIE")
                require(cie.pointerEncoding and 0x80 == 0) { "Indirect unwind code pointers are unsupported" }
                val start = pointer(record, cie.pointerEncoding, section.address + recordStart)
                val size = pointer(record, cie.pointerEncoding and 15, 0)
                require(start >= 0 && size >= 0 && size <= Long.MAX_VALUE - start) { "Invalid unwind function range" }
                if (cie.augmented) record.skip(record.uleb())
                if (size > 0) {
                    image.virtualBytes(start, size, executable = true)
                    yield(Frame(start, start + size, cie, record.block(record.remaining)))
                }
            }
        }
    }

    fun function(symbol: ElfImage.Symbol): Frame {
        require(symbol.type == 2 && symbol.size > 0) { "Expected a defined ELF function" }
        val match = image.unwindFrames[symbol.address]
        require(match != null) { "Missing or ambiguous unwind range for ${symbol.name}" }
        return match.also {
            require(it.end - it.start == symbol.size) { "ELF function and unwind ranges disagree: ${symbol.name}" }
        }
    }

    private fun readCommon(offset: Long, recordStart: Long, record: BinaryCursor): Common {
        val version = record.unsigned(1)
        require(version == 1L || version == 3L || version == 4L) { "Unsupported unwind CIE version" }
        val augmentation = record.string()
        require(augmentation.isEmpty() || augmentation.startsWith('z')) { "Unsupported unwind augmentation" }
        require(augmentation.length <= 16) { "Unwind augmentation exceeds bound" }
        if (version == 4L) {
            require(record.unsigned(1) == 8L && record.unsigned(1) == 0L) { "Unsupported unwind address format" }
        }
        val codeAlignment = record.uleb()
        val dataAlignment = record.sleb()
        val returnRegister = if (version == 1L) record.unsigned(1) else record.uleb()
        require(codeAlignment > 0 && dataAlignment != 0L && returnRegister == 16L) {
            "Unsupported x64 unwind register or alignment"
        }
        var encoding = 0
        if (augmentation.isNotEmpty()) {
            val length = record.uleb()
            val address = section.address + recordStart + record.position
            val data = record.block(length).cursor()
            require(augmentation.drop(1).toSet().size == augmentation.length - 1) { "Repeated unwind augmentation" }
            for (flag in augmentation.drop(1)) {
                when (flag) {
                    'L' -> data.unsigned(1)
                    'P' -> {
                        val personalityEncoding = data.unsigned(1).toInt()
                        // Parse the encoded location without dereferencing a personality/GOT pointer.
                        pointer(data, personalityEncoding and 0x7f, address)
                    }

                    'R' -> encoding = data.unsigned(1).toInt()
                    'S' -> Unit
                    else -> error("Unsupported unwind augmentation: $flag")
                }
            }
            require(data.remaining == 0L) { "Unconsumed unwind augmentation data" }
        }
        return Common(
            offset, codeAlignment, dataAlignment, returnRegister, encoding, augmentation.isNotEmpty(),
            record.block(record.remaining)
        )
    }

    private fun pointer(cursor: BinaryCursor, encoding: Int, base: Long): Long {
        val location = cursor.position
        val format = encoding and 15
        val value = when (format) {
            0 -> cursor.unsigned(8)
            1 -> cursor.uleb()
            2 -> cursor.unsigned(2)
            3 -> cursor.unsigned(4)
            4 -> cursor.unsigned(8)
            9 -> cursor.sleb()
            10 -> cursor.unsigned(2).toShort().toLong()
            11 -> cursor.unsigned(4).toInt().toLong()
            12 -> cursor.unsigned(8)
            else -> error("Unsupported unwind pointer representation")
        }
        val relative = when (encoding and 0xf0) {
            0 -> 0L
            0x10 -> {
                require(base >= 0 && base <= Long.MAX_VALUE - location) { "Unwind location overflow" }
                base + location
            }

            else -> error("Unsupported unwind pointer base")
        }
        require((value >= 0 && value <= Long.MAX_VALUE - relative) || (value < 0 && value >= -relative)) {
            "Unwind pointer overflow"
        }
        return relative + value
    }
}
