@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ScalarBranchPathTest {
    @Test
    fun resolvesCompiledAdmissionWithVariedInputFieldsAndConstants() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/scalar_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val extent = constant("fixture_mask_extent")
            val type = constant("fixture_mask_type")
            val code = constant("fixture_mask_code")
            val flow = X64ControlFlow.resolve(image, image.symbol("fixture_gate"))
            val arguments = SysVArgumentFlow(flow)
            val site = flow.instructions.single {
                arguments.source(it.offset) == SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, code), 4)
            }.offset
            val analysis = ScalarBranchPath(flow, mapOf(7 to extent))
            fun resolve(value: Long) = analysis.to(site) {
                assertEquals(SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, type), 4), it.field)
                value
            }
            assertEquals(site, resolve(padding.toLong()).last())
            for (other in listOf(-1, 0, padding - 1, padding + 1)) assertFails { resolve(other.toLong()) }
        }
    }

    @Test
    fun rejectsUnknownPredicatesMutationsCallsAndLoops() {
        fun resolve(code: String, value: Long = 1): List<Long> {
            val flow = X64ControlFlow(X64Instructions(machineCode(code)).all())
            val site = flow.instructions.single { it.operation == Operation.NOP }.offset
            return ScalarBranchPath(flow, mapOf(7 to 16L)).to(site) { value }
        }

        val admitted = "8b 07 83 f8 01 75 01 90 c3"
        assertEquals(7L, resolve(admitted).last())
        assertFails { resolve(admitted, 2) }
        assertEquals(10L, resolve("8b 07 8d 50 ff 83 fa 02 73 01 90 c3").last())
        assertFails { resolve("8b 07 8d 50 ff 83 fa 02 73 01 90 c3", 3) }
        assertEquals(14L, resolve("55 48 89 e5 89 45 f8 $admitted").last())
        assertFails { resolve("89 07 $admitted") }
        assertFails { resolve("55 48 89 e5 89 45 08 $admitted") }
        assertFails { resolve("55 48 89 e5 f0 ff 4d f8 $admitted") }
        assertFails { resolve("55 e8 00 01 00 00 $admitted") }
        assertFails { resolve("83 f8 01 75 01 90 c3") }
        assertFails { resolve("8b 07 83 f8 01 ff c1 75 01 90 c3") }
        assertFails { resolve("8b 07 83 f8 01 74 f9 90 c3") }
        assertFails { resolve("8b 47 10 83 f8 01 75 01 90 c3") }
    }
}
