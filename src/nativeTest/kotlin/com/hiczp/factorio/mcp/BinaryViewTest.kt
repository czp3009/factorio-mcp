package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BinaryViewTest {
    @Test
    fun copiesNestedSlicesWithoutReadingOutsideTheirBoundsOrAliasingStorage() {
        val storage = ByteArray(64) { it.toByte() }
        for (view in listOf(BinaryView(storage), BinaryView(64, { storage[it.toInt()] }))) {
            val selected = view.slice(10, 20).slice(5, 7)
            val bytes = selected.bytes(1, 4)
            assertContentEquals(byteArrayOf(16, 17, 18, 19), bytes)
            bytes[0] = 99
            assertEquals(16, selected.unsigned(1, 1))
            assertContentEquals(byteArrayOf(), selected.bytes(7, 0))
            for ((offset, count) in listOf(-1L to 1, 7L to 1, 8L to 0, 0L to -1, Long.MAX_VALUE to 1))
                assertFailsWith<IllegalArgumentException> { selected.bytes(offset, count) }
        }
    }

    @Test
    fun validatesRangesBeforeCallingBulkStorage() {
        var copies = 0
        val view = BinaryView(64, { error("Unexpected scalar read") }, { offset, length ->
            ++copies
            ByteArray(length) { (offset + it).toByte() }
        }).slice(8, 24).slice(3, 9)
        assertContentEquals(byteArrayOf(13, 14, 15), view.bytes(2, 3))
        assertEquals(1, copies)
        assertFailsWith<IllegalArgumentException> { view.bytes(8, 2) }
        assertFailsWith<IllegalArgumentException> { view.bytes(Long.MAX_VALUE, Int.MAX_VALUE) }
        assertEquals(1, copies)
    }

    @Test
    fun rejectsOverflowAndTruncatedRanges() {
        val data = BinaryView(byteArrayOf(1, 2, 3, 4))
        assertEquals(0x04030201, data.unsigned(0, 4))
        assertEquals(0x0302, data.slice(1, 2).unsigned(0, 2))
        assertFailsWith<IllegalArgumentException> { data.slice(Long.MAX_VALUE, 4) }
        assertFailsWith<IllegalArgumentException> { data.unsigned(3, 2) }
        assertFailsWith<IllegalArgumentException> { data.slice(-1, 1) }
        assertFailsWith<IllegalStateException> { data.string(0) }
    }

    @Test
    fun decodesLebIncludingFullWidthAndRejectsOverflow() {
        fun cursor(vararg values: Int) = BinaryView(values.map(Int::toByte).toByteArray()).cursor()
        assertEquals(624485, cursor(0xe5, 0x8e, 0x26).uleb())
        assertEquals(-624485, cursor(0x9b, 0xf1, 0x59).sleb())
        assertEquals(-1, cursor(0x7f).sleb())
        assertEquals(Long.MIN_VALUE, cursor(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x7f).sleb())
        assertEquals(-1, cursor(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 1).uleb())
        assertFailsWith<IllegalArgumentException> {
            cursor(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 2).uleb()
        }
        assertFailsWith<IllegalArgumentException> { cursor(0x80).uleb() }
    }

    @Test
    fun advancesPastUtf8StringsByBytes() {
        val cursor = BinaryView("菜单\u0000x\u0000".encodeToByteArray()).cursor()
        assertEquals("菜单", cursor.string())
        assertEquals("x", cursor.string())
        assertEquals(0, cursor.remaining)
    }
}
