@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class ControlWheelKindsTest {
    @Test
    fun derivesWheelCodesFromCompleteInlineAndAllocatedConfigurationStrings() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val results = listOf(1, 23).map { padding ->
            MappedBinary("$directory/control_wheel_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String) =
                    image.symbol("fixture_$name").let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

                val extent = constant("size")
                val type = constant("type")
                val code = constant("code")
                val first = constant("first_code").toInt()
                val function = image.symbol("_ZNK5Value4saveEv")
                val string = NativeStringLayout(
                    constant("string_data"), constant("string_length"),
                    constant("string_local"), constant("string_size"), image.symbol("_ZN6StringD1Ev")
                )
                val expected = ControlWheelKinds(
                    constant("kind").toInt(),
                    listOf("left", "up", "right", "down").mapIndexed { index, name -> first + index to name }.toMap()
                )
                assertEquals(
                    expected, ControlWheelKinds.resolve(
                        image, extent, type, code, string,
                        function.name, "fixture_allocate", "fixture_prefix"
                    )
                )
                val tables = X64JumpTables.resolve(image, function)
                val flow = X64ControlFlow.resolve(image, function)
                val allocate = image.symbol("fixture_allocate").address - function.address
                val consume = image.symbol("fixture_prefix").address - function.address
                fun read(address: Long, width: Int) = image.virtualBytes(address, width.toLong()).bytes(0, width)
                fun analyze(
                    instructions: List<X64Instructions.Instruction> = flow.instructions,
                    layout: NativeStringLayout = string, field: Long = code,
                    reader: (Long, Int) -> ByteArray = ::read
                ) =
                    ControlWheelKinds.analyze(
                        X64ControlFlow(instructions, tables), tables, extent, type, field,
                        layout, function.address, allocate, consume, reader
                    )
                assertFails { analyze(field = code + 1) }
                assertFails { analyze(layout = string.copy(length = string.length + 1)) }
                assertFails { analyze(reader = { address, width -> read(address, width).also { it[0] = 0 } }) }
                assertFails {
                    analyze(flow.instructions.map {
                        if (it.operation == Operation.MOV && it.destination == Register(7, 4) && it.source is Immediate)
                            it.copy(source = Immediate(1)) else it
                    })
                }
                assertFails {
                    analyze(flow.instructions.map {
                        if (it.operation == Operation.CALL && it.destination == Immediate(allocate))
                            it.copy(destination = Immediate(allocate + 1)) else it
                    })
                }
                assertFails {
                    analyze(flow.instructions.map {
                        if (it.operation == Operation.XOR && it.destination == Register(6, 4))
                            it.copy(operation = Operation.MOV, source = Immediate(1)) else it
                    })
                }
                assertFails {
                    analyze(flow.instructions.map {
                        if (it.operation == Operation.MOV && it.destination is Memory && it.destination.width in 1..2 &&
                            it.source is Immediate && it.source.value and 255 == 0L
                        )
                            it.copy(source = Immediate(1)) else it
                    })
                }
                expected
            }
        }
        assertNotEquals(results[0].kind, results[1].kind)
        assertNotEquals(results[0].names.keys, results[1].names.keys)
    }

    @Test
    fun rejectsModifiedOrForeignCodeDiscriminators() {
        val input = ScalarExpression.Input(0, SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, 24), 4), 4)
        val adjusted = ScalarExpression.Binary(Operation.SUB, input, ScalarExpression.Literal(9, 4), 4)
        assertEquals(-9, ControlWheelKinds.codeAdjustment(adjusted, 24))
        for (value in listOf(
            adjusted.copy(operation = Operation.XOR),
            adjusted.copy(right = input),
            adjusted.copy(width = 2),
            input.copy(field = input.field.copy(reference = SysVArgumentFlow.Reference(7, 24))),
            ScalarExpression.Narrow(adjusted, 1),
        )) assertFails { ControlWheelKinds.codeAdjustment(value, 24) }
    }
}
