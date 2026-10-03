@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ItaniumSubobjectVtableTest {
    private fun scalar(value: Long) = ElfPointers.Word(value, null, true)
    private fun pointer(value: Long) = ElfPointers.Word(0, value, true)

    @Test
    fun resolvesCompiledSecondaryInterfaceWithoutAssumingCompleteObjectSize() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val offsets = mutableListOf<Long>()
        for (padding in listOf(1, 23)) MappedBinary("$directory/game_state_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            val table = ItaniumSubobjectVtable.resolveInterface(image, "7Manager", "9Predicate")
            val method = table.method(image, "_ZNK9Predicate7observeEv")
            assertTrue(table.baseOffset > 0)
            assertEquals(table.addressPoint, method.addressPoint)
            offsets += table.baseOffset
        }
        assertTrue(offsets[0] != offsets[1])
    }

    @Test
    fun selectsMatchingRttiDisplacementAcrossReorderedSecondaryGroups() {
        val words = listOf(scalar(0), pointer(1000), pointer(2000), pointer(2010),
            scalar(-96), pointer(1000), pointer(2020), scalar(0), pointer(2030),
            scalar(-24), pointer(1000), pointer(2040), pointer(2050))
        val table = ItaniumSubobjectVtable.analyze(8192, 1000, 24, 160, words,
            setOf(2000, 2010, 2020, 2030, 2040, 2050))
        assertEquals(8192L + 11 * 8, table.addressPoint)
        assertEquals(listOf(2040L, 2050L), table.entries)
        assertEquals(mapOf(8192L to 0L, 8224L to -96L, 8264L to -24L), table.scalars)
        assertEquals(0L, table.pointers[8248])
    }

    @Test
    fun rejectsUnboundedAmbiguousAndUnrelocatedTables() {
        val words = listOf(scalar(0), pointer(1000), pointer(2000),
            scalar(-24), pointer(1000), pointer(2010))
        fun analyze(input: List<ElfPointers.Word>, offset: Long = 24, size: Long = 64) =
            ItaniumSubobjectVtable.analyze(8192, 1000, offset, size, input, setOf(2000, 2010))
        assertFails { analyze(words, 16) }
        assertFails { analyze(words, size = 24) }
        assertFails { analyze(words + listOf(scalar(-24), pointer(1000), pointer(2000))) }
        assertFails { analyze(words.toMutableList().also { it[4] = pointer(1008) }) }
        assertFails { analyze(words.toMutableList().also { it[5] = pointer(2020) }) }
        assertFails { analyze(words.toMutableList().also { it[5] = scalar(2010) }) }
        assertFails { analyze(words.toMutableList().also { it[0] = scalar(-8) }) }
        assertFails { analyze(words.take(5)) }
    }
}
