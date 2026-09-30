package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrameApiBindingTest {
    private val slots = FrameApiBinding.names.withIndex().associate { it.value to 0x1000L + it.index * 8 }
    private val mappings = ProcMapping.parse("1000-2000 rw-p 00000000 00:00 0\n3000-4000 r-xp 00000000 00:00 0\n")
    private fun word(value: Long) = ByteArray(8) { (value ushr (it * 8)).toByte() }

    @Test
    fun bindsReadableSlotsToExecutableEntries() {
        val result = FrameApiBinding.bind(slots, 0, mappings) { _, _ -> word(0x3000) }
        assertEquals(slots, result.mapValues { it.value.storage })
        assertEquals(setOf(0x3000L), result.values.map { it.function }.toSet())
    }

    @Test
    fun rejectsAbsentTruncatedAndNonExecutableEntries() {
        for (function in listOf(0L, -1L, 0x1000L, 0x4000L)) {
            assertFailsWith<IllegalArgumentException> {
                FrameApiBinding.bind(slots, 0, mappings) { _, _ -> word(function) }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            FrameApiBinding.bind(slots, 0, mappings) { _, _ -> ByteArray(7) }
        }
        assertFailsWith<IllegalArgumentException> {
            FrameApiBinding.bind(slots, 0x1000, mappings) { _, _ -> error("Unreadable slot was read") }
        }
        assertFailsWith<IllegalArgumentException> {
            FrameApiBinding.bind(slots.mapValues { 0x1000 }, 0, mappings) { _, _ -> word(0x3000) }
        }
    }
}
