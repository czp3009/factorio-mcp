package com.hiczp.factorio.mcp

import kotlin.random.Random
import kotlin.test.*
import kotlin.math.abs
import kotlinx.coroutines.*

class ImageEncoderTest {
    private fun png(width: Int, height: Int, rgb: ByteArray) =
        ImageEncoder.encode(RgbImage(width, height, rgb), ImageFormat.PNG).bytes

    @Test
    fun preservesOddWidthRowsAndRepeatedEncodes() {
        repeat(3) { seed ->
            val rgb = Random(seed).nextBytes(7 * 3 * 3)
            assertContentEquals(rgb, decodeImage(png(7, 3, rgb)).pixels)
        }
    }

    @Test
    fun writesValidChunksAndLosslessTopDownRows() {
        val rgb = byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1)
        val png = png(2, 2, rgb)
        assertContentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), png.copyOfRange(0, 8))
        fun integer(at: Int): Int =
            (0..3).fold(0) { value, i -> (value shl 8) or (png[at + i].toInt() and 255) }
        val names = mutableListOf<String>()
        var at = 8
        while (at < png.size) {
            val size = integer(at)
            val name = png.decodeToString(at + 4, at + 8)
            names += name
            // Independent bitwise PNG CRC verification.
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
            at += size + 12
        }
        assertEquals("IHDR", names.first())
        assertTrue("IDAT" in names)
        assertEquals("IEND", names.last())
        assertContentEquals(rgb, decodeImage(png).pixels)
        assertEquals(png.size, at)
    }

    @Test
    fun compressesLargeFramesAndRejectsInvalidStorage() {
        val image = png(2048, 2048, ByteArray(2048 * 2048 * 3))
        assertTrue(image.size < 100_000 && image.size <= ImageEncoder.MAX_BYTES)
        assertFails { png(0, 1, byteArrayOf()) }
        assertFails { png(8192, 8192, byteArrayOf()) }
        assertFails { png(2, 2, ByteArray(11)) }
    }

    @Test
    fun rejectsEncodedOutputAboveTheLimit() {
        val noise = Random(82341).nextBytes(4096 * 1536 * 3)
        assertFailsWith<IllegalStateException> { png(4096, 1536, noise) }
    }

    @Test
    fun jpegPreservesDimensionsColorsAndTopDownRows() {
        val width = 127
        val height = 65
        val pixels = ByteArray(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) {
            val i = (y * width + x) * 3
            pixels[i] = (x * 255 / (width - 1)).toByte()
            pixels[i + 1] = (y * 255 / (height - 1)).toByte()
            pixels[i + 2] = 60
        }
        val source = RgbImage(width, height, pixels)
        repeat(3) {
            val image = ImageEncoder.encode(source, ImageFormat.JPG)
            assertContentEquals(byteArrayOf(-1, -40), image.bytes.copyOf(2))
            assertContentEquals(byteArrayOf(-1, -39), image.bytes.takeLast(2).toByteArray())
            val decoded = decodeImage(image.bytes)
            assertEquals(width, decoded.width)
            assertEquals(height, decoded.height)
            val meanError = pixels.indices.sumOf { i ->
                abs((pixels[i].toInt() and 255) - (decoded.pixels[i].toInt() and 255)).toLong()
            }.toDouble() / pixels.size
            assertTrue(meanError < 5, "JPEG RGB error: $meanError")
        }
    }

    @Test
    fun jpegQualityControlsCompressionAndRetainsTheDefault() {
        val samples = Random(8531).nextBytes(127 * 65)
        val source = RgbImage(127, 65, ByteArray(samples.size * 3) { samples[it / 3] })
        val default = ImageEncoder.encode(source, ImageFormat.JPG)
        val explicitDefault = ImageEncoder.encode(source, ImageFormat.JPG, 85)
        assertEquals(85, default.quality)
        assertContentEquals(default.bytes, explicitDefault.bytes)
        val low = ImageEncoder.encode(source, ImageFormat.JPG, 1)
        val high = ImageEncoder.encode(source, ImageFormat.JPG, 100)
        assertEquals(1, low.quality)
        assertEquals(100, high.quality)
        assertTrue(low.bytes.size < high.bytes.size)
        fun error(image: EncodedImage): Long {
            val decoded = decodeImage(image.bytes)
            assertEquals(source.width, decoded.width)
            assertEquals(source.height, decoded.height)
            return source.pixels.indices.sumOf { i ->
                abs((source.pixels[i].toInt() and 255) - (decoded.pixels[i].toInt() and 255)).toLong()
            }
        }
        assertTrue(error(high) < error(low))
        assertNull(ImageEncoder.encode(source, ImageFormat.PNG).quality)
        for (quality in listOf(Int.MIN_VALUE, -1, 0, 101, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { ImageEncoder.encode(source, ImageFormat.JPG, quality) }
        }
        assertFailsWith<IllegalArgumentException> { ImageEncoder.encode(source, ImageFormat.PNG, 85) }
    }

    @Test
    fun downsamplesWholeImageWithAreaAveragesAndEncodesBothFormats() = runBlocking<Unit> {
        val source = RgbImage(4, 4, ByteArray(48) { i ->
            val pixel = i / 3
            ((pixel / 4) * 40 + pixel % 4 * 10).toByte()
        })
        val resized = ImageEncoder.resize(source, 2)
        assertEquals(2, resized.width)
        assertEquals(2, resized.height)
        assertContentEquals(byteArrayOf(25, 25, 25, 45, 45, 45, 105, 105, 105, 125, 125, 125), resized.pixels)
        for (format in ImageFormat.entries) {
            val decoded = decodeImage(ImageEncoder.encode(resized, format).bytes)
            assertEquals(2, decoded.width)
            assertEquals(2, decoded.height)
            if (format == ImageFormat.PNG) assertContentEquals(resized.pixels, decoded.pixels)
        }
    }

    @Test
    fun preservesSizeWithoutEnlargingAndRoundsNarrowAndOddAspectRatios() = runBlocking<Unit> {
        val source = RgbImage(7, 5, ByteArray(105) { 90 })
        for (height in listOf(null, 5, 100, Int.MAX_VALUE)) {
            assertSame(source, ImageEncoder.resize(source, height))
        }
        val odd = ImageEncoder.resize(source, 3)
        assertEquals(4, odd.width)
        assertEquals(3, odd.height)
        assertTrue(odd.pixels.all { it == 90.toByte() })
        val narrow = ImageEncoder.resize(RgbImage(1, 8192, ByteArray(8192 * 3) { 60 }), 1)
        assertEquals(1, narrow.width)
        assertEquals(1, narrow.height)
        assertContentEquals(byteArrayOf(60, 60, 60), narrow.pixels)
        assertFailsWith<IllegalArgumentException> { ImageEncoder.resize(source, 0) }
        assertFailsWith<IllegalArgumentException> { ImageEncoder.resize(source, -1) }
    }

    @Test
    fun resizingCooperatesWithCancellation() = runBlocking<Unit> {
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            ImageEncoder.resize(RgbImage(128, 128, ByteArray(128 * 128 * 3)), 64)
        }
        task.cancel()
        assertFailsWith<CancellationException> { task.await() }
    }
}

internal expect fun decodeImage(bytes: ByteArray): RgbImage
