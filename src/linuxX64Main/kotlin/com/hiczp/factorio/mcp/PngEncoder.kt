@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.*
import platform.zlib.*

/** Bounded RGB PNG serialization outside the game's render callback. Rows must already be top-down. */
internal object PngEncoder {
    const val MAX_BYTES = 16 * 1024 * 1024

    fun encode(width: Int, height: Int, rgb: ByteArray): ByteArray {
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216) {
            "Frame dimensions exceed capture bounds"
        }
        val stride = width * 3
        require(rgb.size == stride * height) { "Invalid RGB frame size" }
        val rows = ByteArray((stride + 1) * height)
        for (row in 0 until height) {
            // PNG filter 0 preserves bytes; compression handles repeated pixel data.
            rgb.copyInto(rows, row * (stride + 1) + 1, row * stride, (row + 1) * stride)
        }
        val compressed = ByteArray(minOf(compressBound(rows.size.toULong()), (MAX_BYTES - 57).toULong()).toInt())
        val compressedSize = memScoped {
            val length = alloc<uLongfVar>()
            length.value = compressed.size.toULong()
            val status = rows.usePinned { input ->
                compressed.usePinned { output ->
                    compress2(
                        output.addressOf(0).reinterpret(), length.ptr, input.addressOf(0).reinterpret(),
                        rows.size.toULong(), Z_BEST_SPEED
                    )
                }
            }
            check(status == Z_OK) { "PNG compression failed or exceeded the image limit: $status" }
            check(length.value in 1uL..compressed.size.toULong())
            length.value.toInt()
        }
        val output = ByteArray(compressedSize + 57)
        byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10).copyInto(output)
        fun integer(offset: Int, value: Int) {
            for (index in 0..3) output[offset + index] = (value ushr (24 - index * 8)).toByte()
        }

        fun chunk(offset: Int, name: String, size: Int, write: (Int) -> Unit): Int {
            integer(offset, size)
            name.encodeToByteArray().copyInto(output, offset + 4)
            write(offset + 8)
            val crc = output.usePinned {
                crc32(0uL, it.addressOf(offset + 4).reinterpret(), (size + 4).toUInt())
            }
            integer(offset + 8 + size, crc.toInt())
            return offset + 12 + size
        }

        var next = chunk(8, "IHDR", 13) { at ->
            integer(at, width)
            integer(at + 4, height)
            output[at + 8] = 8
            output[at + 9] = 2
        }
        next = chunk(next, "IDAT", compressedSize) { compressed.copyInto(output, it, 0, compressedSize) }
        check(chunk(next, "IEND", 0) {} == output.size)
        return output
    }
}
