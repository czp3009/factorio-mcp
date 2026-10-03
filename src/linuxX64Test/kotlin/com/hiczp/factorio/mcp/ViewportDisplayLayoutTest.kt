package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ViewportDisplayLayoutTest {
    private fun displacement(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }

    private fun getter(renderer: Int, framebuffer: Int): ByteArray =
        byteArrayOf(0x53, 0x48, 0x8b.toByte(), 0x87.toByte()) + displacement(renderer) +
                byteArrayOf(0x48, 0x8b.toByte(), 0x98.toByte()) + displacement(framebuffer) +
                byteArrayOf(0x48, 0x8b.toByte(), 0x3b, 0x48, 0x8b.toByte(), 0x07,
                    0xff.toByte(), 0x50, 24,
                    0x48, 0x8b.toByte(), 0x3b, 0x48, 0x8b.toByte(), 0x07,
                    0xff.toByte(), 0x50, 64, 0x5b, 0xc3.toByte())

    @Test
    fun dimensionsShareOneBoundedTypedRendererReference() {
        for ((renderer, framebuffer) in listOf(40 to 80, 104 to 240)) {
            val bytes = BinaryView(getter(renderer, framebuffer))
            assertEquals(framebuffer.toLong(), ViewportDisplayLayout.analyze(
                bytes, 0x1000, 256, renderer.toLong(), 512, 3, 8))
            assertFails { ViewportDisplayLayout.analyze(bytes, 0x1000, 256, renderer.toLong() + 8, 512, 3, 8) }
            assertFails { ViewportDisplayLayout.analyze(bytes, 0x1000, renderer.toLong() + 7, renderer.toLong(), 512, 3, 8) }
            assertFails { ViewportDisplayLayout.analyze(bytes, 0x1000, 256, renderer.toLong(), framebuffer.toLong() + 7, 3, 8) }
            assertFails { ViewportDisplayLayout.analyze(bytes, 0x1000, 256, renderer.toLong(), 512, 8, 8) }
            val otherArgument = getter(renderer, framebuffer).also { it[3] = 0x86.toByte() }
            assertFails { ViewportDisplayLayout.analyze(BinaryView(otherArgument), 0x1000, 256, renderer.toLong(), 512, 3, 8) }
        }
    }
}
