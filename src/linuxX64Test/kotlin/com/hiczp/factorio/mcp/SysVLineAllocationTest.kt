@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SysVLineAllocationTest {
    @Test
    fun derivesCompilerAllocationsAtDebugLineBoundaries() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/line_allocation_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            val entry = image.symbol("fixture_allocate")
            val code = image.functionBytes(entry, 512)
            val allocator = X64Instructions(code).all().single {
                it.operation == Operation.CALL && (it.destination as? Immediate)?.value?.let { relative ->
                    image.importedFunction(entry.address + relative) == "_Znwm"
                } == true
            }.destination as Immediate
            val rows = DwarfLines(image.section(".debug_line")).addresses(entry.address, entry.address + entry.size)
            val expected = image.symbol("fixture_object_size").let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            assertEquals(
                expected, SysVLineAllocation.analyze(
                    code, entry.address, rows,
                    entry.address + allocator.value, image.symbol("_ZN6ObjectC2EPv").address
                )
            )
        }
    }

    @Test
    fun rejectsUnanchoredChangedTruncatedAndInterruptedAllocationProvenance() {
        val valid = "bf 40 00 00 00 e8 f6 00 00 00 48 89 c7 e8 ee 01 00 00 c3"
        fun analyze(code: String, boundaries: Set<Long> = setOf(0x1000)) =
            SysVLineAllocation.analyze(machineCode(code), 0x1000, boundaries, 0x1100, 0x1200)
        assertEquals(64L, analyze(valid))
        assertFails { analyze(valid, emptySet()) }
        assertFails { analyze(valid, setOf(0x1001)) }
        for (changed in listOf(
            valid.replace("bf 40", "be 40"),
            valid.replace("48 89 c7", "48 89 cf"),
            valid.replace("48 89 c7", "89 c7 90"),
            valid.replace("48 89 c7", "48 89 07"),
            valid.replace("48 89 c7", "eb 01 90"),
            valid.replace("e8 ee 01", "e8 ef 01"),
            valid.replace("e8 f6 00", "e8 f7 00"),
        )) assertFails { analyze(changed) }
    }
}
