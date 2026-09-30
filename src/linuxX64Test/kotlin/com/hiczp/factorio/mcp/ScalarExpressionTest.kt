@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import com.hiczp.factorio.mcp.X64Instructions.Register
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ScalarExpressionTest {
    @Test
    fun preservesNativeIntegerWidthsConditionsAndShiftCounts() {
        fun literal(value: Long, width: Int) = ScalarExpression.Literal(value, width)
        fun evaluate(value: ScalarExpression.Value) = ScalarExpression.evaluate(value) { error("No input") }
        for (width in listOf(1, 2, 4)) {
            val maximum = (1L shl (width * 8)) - 1
            for (condition in listOf(2, 3, 4, 5, 6, 7, 12, 13, 14, 15)) {
                val expected = when (condition) {
                    3, 5, 7, 12, 14 -> 19L
                    else -> 31L
                }
                assertEquals(
                    expected, evaluate(
                        ScalarExpression.Select(
                            condition, literal(maximum, width),
                            literal(1, width), literal(19, width), literal(31, width), width
                        )
                    )
                )
            }
            assertEquals(
                0L,
                evaluate(ScalarExpression.Binary(Operation.ADD, literal(maximum, width), literal(1, width), width))
            )
            assertEquals(
                maximum,
                evaluate(ScalarExpression.Binary(Operation.SUB, literal(0, width), literal(1, width), width))
            )
            for (shift in 0..63) {
                assertEquals(
                    (1L shl (shift and 31)) and maximum,
                    evaluate(
                        ScalarExpression.Binary(
                            Operation.SHL,
                            literal(1, width),
                            literal(shift.toLong(), 1),
                            width
                        )
                    )
                )
                assertEquals(
                    maximum, evaluate(
                        ScalarExpression.Binary(
                            Operation.SAR, literal(maximum, width),
                            literal(shift.toLong(), 1), width
                        )
                    )
                )
            }
        }
    }

    @Test
    fun matchesCompiledSignedGuardsUnsignedGuardsAndMaskedShifts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/scalar_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val extent = constant("fixture_mask_extent")
            val typeField = constant("fixture_mask_type")
            val codeField = constant("fixture_mask_code")
            val flow = X64ControlFlow.resolve(image, image.symbol("fixture_mask"))
            val site = flow.instructions.single { it.operation == Operation.RET }.offset
            val value = ScalarExpression(flow, mapOf(7 to extent)).before(site, Register(0, 2))
            assertEquals(
                setOf(typeField, codeField),
                ScalarExpression.inputs(value).map { it.field.reference.offset }.toSet()
            )
            for (type in listOf(-1, 0, padding - 2, padding - 1, padding, 100)) for (code in -35..64) {
                val expected = if (code >= padding || (type.toUInt() - padding.toUInt()) < 0xfffffffeu) 1
                else (1 shl (code and 31)) and 65535
                assertEquals(expected.toLong(), ScalarExpression.evaluate(value) { input ->
                    assertEquals(7, input.field.reference.argument)
                    assertEquals(4, input.field.width)
                    if (input.field.reference.offset == typeField) type.toLong() else code.toLong()
                })
            }
            assertFails { ScalarExpression(flow, mapOf(7 to codeField)).before(site, Register(0, 2)) }
            assertFails { ScalarExpression(flow, mapOf(6 to extent)).before(site, Register(0, 2)) }
            val kindFlow = X64ControlFlow.resolve(image, image.symbol("fixture_kind"))
            val kindSite = kindFlow.instructions.single { it.operation == Operation.RET }.offset
            val kind = ScalarExpression(kindFlow, mapOf(7 to extent)).before(kindSite, Register(0, 4))
            assertEquals(setOf(typeField), ScalarExpression.inputs(kind).map { it.field.reference.offset }.toSet())
            for (type in listOf(-1, 0, padding - 1, padding, padding + 1)) {
                assertEquals(if (type == padding) 8L else 7L, ScalarExpression.evaluate(kind) { type.toLong() })
            }
        }
    }

    @Test
    fun rejectsClobbersAmbiguousDefinitionsUninitializedRegistersAndUnsupportedFlags() {
        fun parse(code: String): ScalarExpression.Value {
            val flow = X64ControlFlow(X64Instructions(machineCode("$code c3")).all())
            return ScalarExpression(flow, mapOf(7 to 16L)).before(flow.instructions.last().offset, Register(0, 4))
        }
        assertEquals(5L, ScalarExpression.evaluate(parse("b8 05 00 00 00")) { error("No input") })
        assertFails { parse("b8 05 00 00 00 e8 00 01 00 00") }
        assertEquals(1L, ScalarExpression.evaluate(parse("b8 05 00 00 00 b0 01")) { error("No input") })
        assertFails { parse("b0 01") }
        assertFails { parse("85 c9 74 07 b8 05 00 00 00 eb 05 b8 09 00 00 00") }
        val tested = parse("8b 07 8b 4f 04 85 c9 0f 45 c1")
        assertEquals(5L, ScalarExpression.evaluate(tested) { if (it.field.reference.offset == 0L) 5 else 0 })
        assertEquals(9L, ScalarExpression.evaluate(tested) { if (it.field.reference.offset == 0L) 5 else 9 })
        assertFails { parse("8b 07 8b 4f 04 39 c8 83 c2 01 0f 45 c1") }
        assertFails { parse("8b 06") }
        assertFails { parse("8b 47 10") }
        assertFails { parse("01 c8") }
    }

    @Test
    fun preservesUpperBitsAcrossByteWordAndConditionalWrites() {
        fun parse(code: String): ScalarExpression.Value {
            val flow = X64ControlFlow(X64Instructions(machineCode("$code c3")).all())
            return ScalarExpression(flow, mapOf(7 to 16L)).before(flow.instructions.last().offset, Register(0, 4))
        }

        fun evaluate(code: String, input: Long = 0) = ScalarExpression.evaluate(parse(code)) { input }
        assertEquals(0x123456abL, evaluate("b8 78 56 34 12 b0 ab"))
        assertEquals(0x1234abcdL, evaluate("b8 78 56 34 12 66 b8 cd ab"))
        assertEquals(0x12345600L, evaluate("b8 ff 56 34 12 fe c0"))
        assertEquals(0x123456ffL, evaluate("b8 00 56 34 12 fe c8"))
        assertEquals(0x12340000L, evaluate("b8 ff ff 34 12 66 ff c0"))
        for (condition in listOf(2, 3, 4, 5, 6, 7, 12, 13, 14, 15)) {
            val opcode = (0x90 + condition).toString(16)
            val expression = parse("b8 ff 56 34 12 83 3f 01 0f $opcode c0")
            for (input in listOf(-1L, 0, 1, 2)) {
                val expected = when (condition) {
                    2 -> input.toUInt() < 1u
                    3 -> input.toUInt() >= 1u
                    4 -> input == 1L
                    5 -> input != 1L
                    6 -> input.toUInt() <= 1u
                    7 -> input.toUInt() > 1u
                    12 -> input < 1
                    13 -> input >= 1
                    14 -> input <= 1
                    else -> input > 1
                }
                assertEquals(0x12345600L + if (expected) 1 else 0, ScalarExpression.evaluate(expression) { input })
            }
        }
        assertEquals(8L, evaluate("31 c0 83 3f 01 0f 94 c0 83 c0 07", 1))
        assertEquals(7L, evaluate("31 c0 83 3f 01 0f 94 c0 83 c0 07", 2))
        assertFails { parse("83 3f 01 0f 94 c0") }
        assertFails { parse("31 c0 83 3f 01 ff c1 0f 94 c0") }
        assertFails { parse("31 c0 83 3f 01 e8 00 01 00 00 0f 94 c0") }
        assertFails { parse("31 c0 83 3f 01 0f 90 c0") }
    }

    @Test
    fun rotatesAtTheOperandWidthAndDoesNotReuseRotateFlagsAsComparisonFlags() {
        for (width in listOf(1, 2, 4)) {
            val bits = width * 8
            val mask = (1L shl bits) - 1
            for (value in listOf(0L, 1, 0x81234567L, 0xffffffffL)) for (count in 0..255) {
                for (operation in listOf(Operation.ROL, Operation.ROR)) {
                    var expected = value and mask
                    repeat((count and 31) % bits) {
                        expected =
                            if (operation == Operation.ROL) ((expected shl 1) or (expected ushr (bits - 1))) and mask
                            else (expected ushr 1) or ((expected and 1) shl (bits - 1))
                    }
                    val expression = ScalarExpression.Binary(
                        operation, ScalarExpression.Literal(value, width),
                        ScalarExpression.Literal(count.toLong(), 1), width
                    )
                    assertEquals(expected, ScalarExpression.evaluate(expression) { error("No input") })
                }
            }
        }
        val flow = X64ControlFlow(X64Instructions(machineCode("31 c0 83 3f 01 d1 c0 0f 94 c0 c3")).all())
        assertFails { ScalarExpression(flow, mapOf(7 to 8L)).before(flow.instructions.last().offset, Register(0, 4)) }
    }
}
