package com.hiczp.factorio.mcp

/** File-derived code/data and explicitly classified relocated pointer words for loaded-image comparison. */
internal data class ElfEvidence(
    val functions: List<ElfImage.Symbol>,
    val readonly: List<ElfImage.ReadonlyRange>,
    val pointers: Map<Long, Long>,
    val scalars: Map<Long, Long>,
) {
    fun verify(image: ElfImage, bias: Long, read: (Long, Int) -> ByteArray) {
        require(bias >= 0 && bias % 8 == 0L && pointers.keys.intersect(scalars.keys).isEmpty())
        require((pointers.keys + scalars.keys).all { it in 8..Long.MAX_VALUE - 8 && it % 8 == 0L })
        fun word(value: Long) = ByteArray(8) { (value ushr (it * 8)).toByte() }
        fun relocated(target: Long): Long {
            require(target >= 0 && target <= Long.MAX_VALUE - bias)
            return if (target == 0L) 0 else target + bias
        }

        fun compare(address: Long, expected: ByteArray) {
            require(
                expected.isNotEmpty() && expected.size <= 16 * 1024 * 1024 && address > 0 &&
                        address <= Long.MAX_VALUE - bias - expected.size
            )
            require(read(address + bias, expected.size).contentEquals(expected)) {
                "Live ELF evidence differs from the selected executable at $address"
            }
        }
        for (function in functions) {
            require(function.size in 1..(16 * 1024 * 1024))
            compare(
                function.address,
                image.functionBytes(function, function.size.toInt()).bytes(0, function.size.toInt())
            )
        }
        for (range in readonly) {
            require(range.size in 1..(16 * 1024 * 1024))
            val expected = image.virtualBytes(range.address, range.size).bytes(0, range.size.toInt())
            for ((address, target) in pointers) {
                val bytes = word(relocated(target))
                for (index in bytes.indices) {
                    val offset = address + index - range.address
                    if (offset in 0 until range.size) expected[offset.toInt()] = bytes[index]
                }
            }
            compare(range.address, expected)
        }
        for ((address, target) in pointers) compare(address, word(relocated(target)))
        for ((address, value) in scalars) compare(address, word(value))
    }
}
