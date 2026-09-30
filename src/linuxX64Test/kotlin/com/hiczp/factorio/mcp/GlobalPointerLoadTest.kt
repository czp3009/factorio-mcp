@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class GlobalPointerLoadTest {
    @Test
    fun resolvesCompiledTypedReceiverAndDeleterBoundsWithVariedLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/input_state_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            assertEquals(constant("fixture_state_size"), SysVObjectSize.resolveDeleter(image, "fixture_delete_state"))
            val function = image.symbol("fixture_read_state")
            val callee = image.symbol("fixture_state_mask")
            val flow = X64ControlFlow.resolve(image, function)
            val site = flow.instructions.single {
                it.operation == Operation.CALL &&
                        it.destination == Immediate(callee.address - function.address)
            }.offset
            val global = image.symbol("fixture_state_context").address
            val extent = constant("fixture_state_context_size")
            assertEquals(
                constant("fixture_state_offset"),
                GlobalPointerLoad(flow, function.address, global, extent).at(site, 7)
            )
            assertFails { GlobalPointerLoad(flow, function.address, global + 8, extent).at(site, 7) }
            assertFails { GlobalPointerLoad(flow, function.address, global, 8).at(site, 7) }
        }
    }

    @Test
    fun rejectsPointerTruncationCallsForeignRootsAndAmbiguousLoads() {
        fun resolve(code: String): Long {
            val flow = X64ControlFlow(X64Instructions(machineCode("$code c3")).all())
            return GlobalPointerLoad(flow, 0, 0x107, 32).at(flow.instructions.last().offset, 7)
        }

        val prefix = "48 8b 05 00 01 00 00"
        assertEquals(16L, resolve("$prefix 48 8b 78 10"))
        assertEquals(16L, resolve("$prefix 48 8b 58 10 48 89 df"))
        assertFails { resolve("$prefix 8b 78 10") }
        assertFails { resolve("$prefix 48 8b 78 20") }
        assertFails { resolve("$prefix 48 8b 79 10") }
        assertFails { resolve("$prefix 48 8b 78 10 e8 00 01 00 00") }
        assertFails { resolve("$prefix 48 8b 78 10 48 83 c7 08") }
        assertFails { resolve("$prefix 85 c9 74 06 48 8b 78 10 eb 04 48 8b 78 18") }
    }
}
