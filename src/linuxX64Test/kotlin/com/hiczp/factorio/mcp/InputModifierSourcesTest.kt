@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class InputModifierSourcesTest {
    @Test
    fun associatesCompiledPackedAndSeparateHandlerBytesWithOutputFields() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/aggregate_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val function = image.symbol("fixture_construct_packed_modifiers")
            val sinkTarget = image.symbol("fixture_dispatch_packed_modifiers")
            val flow = X64ControlFlow.resolve(image, function)
            val sink = flow.instructions.single {
                it.operation == Operation.CALL &&
                        (it.destination as? Immediate)?.value?.plus(function.address) == sinkTarget.address
            }.offset
            val output = InputModifierSources(
                constant("fixture_event_alt"), constant("fixture_event_control"),
                constant("fixture_event_shift")
            )
            val arguments = SysVArgumentFlow(flow)
            assertTrue(
                flow.instructions.any { arguments.source(it.offset)?.width == 2 },
                "Fixture must include a packed load"
            )
            val result = InputModifierSources.resolve(
                flow, sink, constant("fixture_input_size"),
                constant("fixture_event_extent").toInt(), output
            )
            assertEquals(
                InputModifierSources(
                    constant("fixture_input_alt"), constant("fixture_input_control"),
                    constant("fixture_input_shift")
                ), result
            )
            assertFails {
                InputModifierSources.resolve(
                    flow, sink, constant("fixture_input_size") - 1,
                    constant("fixture_event_extent").toInt(), output
                )
            }
            assertFails {
                InputModifierSources.resolve(
                    flow, sink, constant("fixture_input_size"),
                    constant("fixture_event_extent").toInt(), output.copy(shift = output.control)
                )
            }
        }
    }

    @Test
    fun rejectsAnotherArgumentMissingCopiesAndAliasedFields() {
        val prefix = "55 48 89 e5 48 83 ec 20"
        val body = "0f b7 47 08 66 89 45 e0 0f b6 47 0a 88 45 e2"
        val suffix = "48 8d 75 e0 e8 e7 01 00 00 48 83 c4 20 5d c3"
        fun analyze(code: String, size: Long = 16): InputModifierSources {
            val flow = X64ControlFlow(X64Instructions(machineCode("$prefix $code $suffix")).all())
            val sink = flow.instructions.last { it.operation == Operation.CALL }.offset
            return InputModifierSources.resolve(flow, sink, size, 3, InputModifierSources(0, 1, 2))
        }
        assertEquals(InputModifierSources(8, 9, 10), analyze(body))
        assertFails { analyze(body.replace("b7 47", "b7 46")) }
        assertFails { analyze(body.replace("b6 47 0a", "b6 47 09")) }
        assertFails { analyze(body.replace("66 89 45 e0", "88 45 e0")) }
        assertFails { analyze("$body c6 45 e1 00") }
        assertFails { analyze(body, 10) }
    }
}
