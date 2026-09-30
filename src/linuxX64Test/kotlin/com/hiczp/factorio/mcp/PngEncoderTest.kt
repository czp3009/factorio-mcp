@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.*
import platform.zlib.Z_OK
import platform.zlib.uLongfVar
import platform.zlib.uncompress
import kotlin.random.Random
import kotlin.test.*

class PngEncoderTest {
    @Test
    fun writesValidChunksAndLosslessTopDownRows() {
        val rgb = byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1)
        val png = PngEncoder.encode(2, 2, rgb)
        assertContentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), png.copyOfRange(0, 8))
        fun integer(at: Int): Int = (0..3).fold(0) { value, i -> (value shl 8) or (png[at + i].toInt() and 255) }
        val names = mutableListOf<String>()
        var at = 8
        while (at < png.size) {
            val size = integer(at)
            val name = png.decodeToString(at + 4, at + 8)
            names += name
            // Independent bitwise PNG CRC verification, not the encoder's zlib helper.
            var crc = -1
            for (i in at + 4 until at + 8 + size) {
                crc = crc xor (png[i].toInt() and 255)
                repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xedb88320.toInt() else 0) }
            }
            assertEquals(crc.inv(), integer(at + 8 + size))
            if (name == "IHDR") {
                assertEquals(13, size)
                assertEquals(2, integer(at + 8))
                assertEquals(2, integer(at + 12))
                assertContentEquals(byteArrayOf(8, 2, 0, 0, 0), png.copyOfRange(at + 16, at + 21))
            }
            if (name == "IDAT") {
                val rows = ByteArray(14)
                memScoped {
                    val length = alloc<uLongfVar>()
                    length.value = rows.size.toULong()
                    rows.usePinned { output ->
                        png.usePinned { input ->
                            assertEquals(
                                Z_OK, uncompress(
                                    output.addressOf(0).reinterpret(), length.ptr,
                                    input.addressOf(at + 8).reinterpret(), size.toULong()
                                )
                            )
                        }
                    }
                    assertEquals(14uL, length.value)
                }
                assertContentEquals(
                    byteArrayOf(0) + rgb.copyOfRange(0, 6) + byteArrayOf(0) + rgb.copyOfRange(6, 12),
                    rows
                )
            }
            at += size + 12
        }
        assertEquals(listOf("IHDR", "IDAT", "IEND"), names)
        assertEquals(png.size, at)
    }

    @Test
    fun compressesLargeFramesAndRejectsInvalidStorage() {
        val image = PngEncoder.encode(2048, 2048, ByteArray(2048 * 2048 * 3))
        assertTrue(image.size < 100_000 && image.size <= PngEncoder.MAX_BYTES)
        assertFails { PngEncoder.encode(0, 1, byteArrayOf()) }
        assertFails { PngEncoder.encode(8192, 8192, byteArrayOf()) }
        assertFails { PngEncoder.encode(2, 2, ByteArray(11)) }
    }

    @Test
    fun rejectsEncodedOutputAboveTheLimit() {
        val noise = Random(82341).nextBytes(4096 * 1536 * 3)
        val failure = assertFailsWith<IllegalStateException> { PngEncoder.encode(4096, 1536, noise) }
        assertTrue(failure.message.orEmpty().contains("image limit"))
    }
}
