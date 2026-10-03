package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SdlPointerCoordinatesTest {
    private fun fixture(width: Int = 8): X64ControlFlow {
        val code = mutableListOf<Instruction>()
        fun add(operation: Operation, target: Operand? = null, source: Operand? = null, control: Int? = null) {
            code += Instruction(code.size.toLong(), 1, operation, target, source, control = control)
        }
        add(Operation.PUSH, Register(5, 8))
        add(Operation.MOV, Register(5, 8), Register(4, 8))
        add(Operation.SUB, Register(4, 8), Immediate(32))
        add(Operation.MOV, Register(12, 8), Register(2, 8))
        add(Operation.VECTOR_MOV, Register(19, width), Memory(12, null, 1, 20, width))
        add(Operation.VECTOR_INTS_TO_FLOATS, Register(19, 16), Register(19, 16))
        add(Operation.VECTOR_MOV, Register(22, 16), Register(16, 16))
        add(Operation.VECTOR_SHUFFLE_FLOATS, Register(22, 16), Register(22, 16), 0)
        add(Operation.VECTOR_MULTIPLY_FLOATS, Register(22, 16), Register(19, 16))
        add(Operation.VECTOR_TRUNCATE_FLOATS, Register(20, 16), Register(22, 16))
        add(Operation.VECTOR_MOV, Memory(4, null, 1, 0, 16), Register(20, 16))
        add(Operation.CALL, Immediate(1000))
        add(Operation.NOP)
        add(Operation.VECTOR_MOV, Register(25, 16), Memory(4, null, 1, 0, 16))
        add(Operation.VECTOR_MOV, Memory(7, null, 1, 48, width), Register(25, width))
        add(Operation.ADD, Register(4, 8), Immediate(32))
        add(Operation.POP, Register(5, 8))
        add(Operation.RET)
        return X64ControlFlow(code)
    }

    @Test
    fun preservesCoordinateMeaningAcrossRegisterAllocationAndPrivateCallSpills() {
        for (width in listOf(8, 16)) {
            val flow = fixture(width)
            assertEquals(List(width / 4) { 20L + it * 4 },
                SdlPointerCoordinates(flow.reaching(14), 36).fields(14, Register(25, width)))
            val shuffle = X64ControlFlow(flow.instructions.map {
                if (it.offset == 7L) it.copy(operation = Operation.VECTOR_SHUFFLE_DWORDS) else it
            })
            assertEquals(List(width / 4) { 20L + it * 4 },
                SdlPointerCoordinates(shuffle.reaching(14), 36).fields(14, Register(25, width)))
        }
    }

    @Test
    fun rejectsMissingScaleWrongConversionAndUninitializedVectorLanes() {
        val original = fixture()
        for ((site, replacement) in listOf(
            6L to original.body.getValue(6).copy(source = Register(17, 16)),
            7L to original.body.getValue(7).copy(control = 85),
            8L to original.body.getValue(8).copy(operation = Operation.VECTOR_ADD_FLOATS),
            9L to original.body.getValue(9).copy(operation = Operation.VECTOR_MOV),
            13L to original.body.getValue(13).copy(source = Memory(4, null, 1, 16, 16)),
        )) {
            val flow = X64ControlFlow(original.instructions.map { if (it.offset == site) replacement else it })
            assertFails { SdlPointerCoordinates(flow.reaching(14), 36).fields(14, Register(25, 8)) }
        }
        assertFails { SdlPointerCoordinates(original.reaching(14), 24).fields(14, Register(25, 8)) }
        assertFails { SdlPointerCoordinates(original.reaching(14), 36).fields(14, Register(25, 16)) }
    }

    @Test
    fun rejectsEscapedCoordinateSpillsAtNativeCalls() {
        val flow = fixture()
        val changed = flow.instructions.map {
            if (it.offset == 6L) it.copy(operation = Operation.LEA, destination = Register(7, 8),
                source = Memory(4, null, 1, 0, 8)) else it
        }
        assertFails { SdlPointerCoordinates(X64ControlFlow(changed).reaching(14), 36) }
    }

    @Test
    fun signedWheelNegationKeepsTheNativeConditionalScalarExpression() {
        val flow = X64ControlFlow(listOf(
            Instruction(0, 1, Operation.MOV, Register(0, 4), Memory(2, null, 1, 16, 4)),
            Instruction(1, 1, Operation.NEG, Register(0, 4)),
            Instruction(2, 1, Operation.RET),
        ))
        val expression = ScalarExpression(flow, mapOf(2 to 44L)).before(2, Register(0, 4))
        for (value in listOf(Int.MIN_VALUE, -71, 0, 17, Int.MAX_VALUE))
            assertEquals((-value).toUInt().toLong(), ScalarExpression.evaluate(expression) { value.toLong() })
    }
}
