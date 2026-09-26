package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PeImageTest {
    private fun ByteArray.put(offset: Int, value: Int, width: Int = 4) {
        repeat(width) { this[offset + it] = (value ushr (8 * it)).toByte() }
    }

    private fun image() =
        ByteArray(1024).apply {
            put(0, 0x5a4d, 2)
            put(60, 128)
            put(128, 0x4550)
            put(132, 0x8664, 2)
            put(134, 1, 2)
            put(148, 240, 2)
            put(152, 0x20b, 2)
            put(260, 16)
            put(404, 512)
            put(408, 512)
            put(412, 512)
        }

    @Test
    fun validatesTruncatedAndOverflowingHeaders() {
        val valid = image()
        PeImage(valid)
        for (length in listOf(0, 1, 63, 128, 151, 300, 420)) {
            assertFailsWith<IllegalArgumentException> { PeImage(valid.copyOf(length)) }
        }
        assertFailsWith<IllegalArgumentException> { PeImage(image().apply { put(60, -1) }) }
        assertFailsWith<IllegalArgumentException> {
            PeImage(image().apply { put(260, Int.MAX_VALUE) })
        }
    }

    @Test
    fun checksDirectoryRecordsAndExportOrdinals() {
        val unwind =
            image().apply {
                put(264 + 3 * 8, 512)
                put(268 + 3 * 8, 12)
                put(512, 800)
                put(516, 820)
            }
        assertEquals(820, PeImage(unwind).functionEnd(800))
        assertFailsWith<IllegalArgumentException> {
            PeImage(unwind.copyOf().apply { put(268 + 3 * 8, 13) }).functionEnd(800)
        }
        val exported =
            image().apply {
                put(264, 512)
                put(268, 40)
                put(532, 1)
                put(536, 1)
                put(540, 600)
                put(544, 604)
                put(548, 608)
                put(600, 800)
                put(604, 700)
                "test".encodeToByteArray().copyInto(this, 700)
            }
        assertEquals(800, PeImage(exported).export("test"))
        PeImage(exported).verifyLoadedHeaders { start, size ->
            exported.copyOfRange(start, start + size)
        }
        val relocated = exported.copyOf().apply { put(152 + 24, 0x76540000) }
        PeImage(exported).verifyLoadedHeaders { start, size ->
            relocated.copyOfRange(start, start + size)
        }
        for (changedOffset in listOf(136)) {
            val loaded =
                exported.copyOf().apply { this[changedOffset] = (this[changedOffset] + 1).toByte() }
            assertFailsWith<IllegalArgumentException> {
                PeImage(exported).verifyLoadedHeaders { start, size ->
                    loaded.copyOfRange(start, start + size)
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            PeImage(exported.copyOf().apply { put(608, 1, 2) }).export("test")
        }
        assertFailsWith<IllegalArgumentException> {
            PeImage(exported.copyOf().apply { put(536, Int.MAX_VALUE) }).export("test")
        }
    }
}
