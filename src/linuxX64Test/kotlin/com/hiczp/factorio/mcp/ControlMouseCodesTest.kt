@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Memory
import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class ControlMouseCodesTest {
    @Test
    fun linksOnlyUnmodifiedBindingAndEventCodesInTheProvenMouseCase() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val kinds = listOf(1, 23).map { padding ->
            MappedBinary("$directory/control_mouse_codes_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String) =
                    image.symbol("fixture_$name").let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

                val function = image.symbol("_ZNK5Value7matchesERK5Event")
                val tables = X64JumpTables.resolve(image, function)
                val flow = X64ControlFlow.resolve(image, function)
                val kind = constant("kind").toInt()
                val code = constant("code")
                val eventCode = constant("event_code")
                fun verify(
                    instructions: List<X64Instructions.Instruction> = flow.instructions,
                    selectedKind: Int = kind, field: Long = eventCode
                ) = ControlEventCode.verify(
                    X64ControlFlow(instructions, tables), tables, constant("size"), constant("type"), code,
                    selectedKind, constant("event_size"), field
                )

                val comparison = verify()
                assertEquals(Operation.CMP, flow.body.getValue(comparison).operation)
                assertFails { verify(selectedKind = kind + 1) }
                assertFails { verify(field = eventCode - 4) }
                assertFails {
                    verify(flow.instructions.map {
                        if (it.operation == Operation.DEC) it.copy(operation = Operation.INC) else it
                    })
                }
                assertFails {
                    verify(flow.instructions.map {
                        if (it.operation == Operation.MOVZX && it.source is Memory)
                            it.copy(source = it.source.copy(base = 6)) else it
                    })
                }
                assertFails {
                    verify(flow.instructions.map {
                        if (it.offset == comparison) it.copy(operation = Operation.TEST) else it
                    })
                }
                val branch = flow.body.getValue(comparison + flow.body.getValue(comparison).size)
                assertFails {
                    verify(flow.instructions.map { if (it == branch) it.copy(condition = 7) else it })
                }
                kind
            }
        }
        assertNotEquals(kinds[0], kinds[1])
    }
}
