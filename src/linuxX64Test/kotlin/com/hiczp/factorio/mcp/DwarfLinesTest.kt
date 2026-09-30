@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class DwarfLinesTest {
    private fun integer(value: Long, width: Int) = List(width) { (value ushr (it * 8) and 255).toInt() }
    private fun table(program: List<Int>, version: Int = 4, minimum: Int = 1): BinaryView {
        val header = listOf(minimum, 1, 1, 251, 14, 13, 0, 1, 1, 1, 1, 0, 0, 0, 1, 0, 0, 1, 0, 0)
        val body = integer(version.toLong(), 2) + (if (version == 5) listOf(8, 0) else emptyList()) +
                integer(header.size.toLong(), 4) + header + program
        return BinaryView((integer(body.size.toLong(), 4) + body).map(Int::toByte).toByteArray())
    }

    private fun address(value: Long) = listOf(0, 9, 2) + integer(value, 8)
    private val end = listOf(0, 1, 1)

    @Test
    fun interpretsAddressOperationsAndExcludesSequenceEnds() {
        val program = address(0x1000) + listOf(1, 2, 4, 3, 127, 1, 8, 1, 41, 9, 3, 0, 1) + end
        for (version in 4..5) {
            assertEquals(
                setOf(0x1000L, 0x1004L, 0x1015L, 0x1017L, 0x101aL),
                DwarfLines(table(program, version)).addresses(0x1000, 0x1020)
            )
            assertEquals(setOf(0x1004L), DwarfLines(table(program, version)).addresses(0x1001, 0x1005))
        }
        assertEquals(
            setOf(0x1000L, 0x1008L),
            DwarfLines(table(address(0x1000) + listOf(1, 2, 4, 1) + end, minimum = 2)).addresses(0, 0x2000)
        )
        assertEquals(
            setOf(0x1000L),
            DwarfLines(table(address(0x1000) + listOf(1) + end + address(0x1000) + listOf(1) + end)).addresses(
                0,
                0x2000
            )
        )
    }

    @Test
    fun rejectsUnknownTruncatedAndOverflowingPrograms() {
        for (program in listOf(
            listOf(1) + end,
            listOf(2, 1) + end,
            address(0x1000) + listOf(1),
            address(0x1000) + listOf(0, 0),
            address(0x1000) + listOf(0, 1, 127) + end,
            address(0x1000) + address(0x0fff) + end,
            address(-1) + end,
            address(Long.MAX_VALUE) + listOf(2, 1, 1) + end,
            listOf(0, 9, 2, 0),
        )) assertFails { DwarfLines(table(program)).addresses(0, Long.MAX_VALUE) }
        assertFails { DwarfLines(table(address(0x1000) + end, minimum = 0)).addresses(0, 0x2000) }
        assertFails { DwarfLines(table(address(0x1000) + end, version = 3)).addresses(0, 0x2000) }
    }

    @Test
    fun matchesCompilerInstructionBoundariesWithDwarf4And5() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (version in 4..5) MappedBinary("$directory/debug_fixture_$version").use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("fixture_function")
            val rows =
                DwarfLines(image.section(".debug_line")).addresses(function.address, function.address + function.size)
            val decoded =
                X64Instructions(image.functionBytes(function, 256)).all().map { function.address + it.offset }.toSet()
            assertTrue(rows.isNotEmpty())
            assertTrue(rows.all { it in decoded })
        }
    }
}
