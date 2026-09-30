@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class KeyMapLayoutTest {
    @Test
    fun derivesPaddedRecordRangesAndRejectsMalformedSearches() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/key_map_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) =
                image.symbol("fixture_key_map_$name").let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

            val flow = X64ControlFlow.resolve(image, image.symbol("fixture_key_map_lookup"))
            val size = constant("size")
            val extent = constant("value_size")
            val result = KeyMapLayout.analyze(flow, size, extent)
            assertEquals(
                KeyMapLayout(
                    constant("begin"), constant("end"), constant("stride"), constant("key"),
                    constant("value")
                ), result
            )
            assertFails { KeyMapLayout.analyze(flow, result.end, extent) }
            assertFails { KeyMapLayout.analyze(flow, size, result.stride) }
            val keyComparisons = flow.instructions.filter {
                it.operation == Operation.CMP && (it.destination as? Memory)?.width == 4
            }
            assertEquals(true, keyComparisons.isNotEmpty())
            assertFails {
                KeyMapLayout.analyze(X64ControlFlow(flow.instructions.map {
                    if (it in keyComparisons) it.copy(destination = (it.destination as Memory).copy(width = 2)) else it
                }), size, extent)
            }
            val returns = flow.instructions.filter { it.operation == Operation.RET }
            assertFails {
                KeyMapLayout.analyze(X64ControlFlow(flow.instructions.map {
                    if (it in returns) it.copy(operation = Operation.CALL, destination = Immediate(-1)) else it
                }), size, extent)
            }
        }
    }
}
