package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class MousePayloadCopyTest {
    private fun fixture(): X64ControlFlow {
        val code = mutableListOf<Instruction>()
        fun add(
            operation: Operation,
            target: Operand? = null,
            source: Operand? = null,
            immediate: Long? = null,
        ) {
            code +=
                Instruction(code.size.toLong(), 1, operation, target, source, immediate = immediate)
        }
        add(Operation.PUSH, Register(5, 8))
        add(Operation.MOV, Register(5, 8), Register(4, 8))
        add(Operation.PUSH, Register(12, 8))
        add(Operation.PUSH, Register(13, 8))
        add(Operation.SUB, Register(4, 8), Immediate(64))
        add(Operation.MOV, Register(12, 8), Register(2, 8))
        add(Operation.MOV, Register(13, 8), Register(7, 8))
        add(Operation.CALL, Immediate(1000))
        add(Operation.MOV, Register(8, 8), Memory(13, null, 1, 8, 8))
        add(Operation.MOV, Register(9, 4), Memory(13, null, 1, 24, 4))
        add(Operation.MULTIPLY_IMMEDIATE, Register(9, 8), Register(9, 8), 96)
        add(Operation.VECTOR_MOV, Register(18, 16), Memory(12, null, 1, 0, 16))
        add(Operation.VECTOR_MOV, Register(19, 16), Memory(12, null, 1, 12, 16))
        add(Operation.VECTOR_MOV, Memory(4, null, 1, 0, 16), Register(18, 16))
        add(Operation.VECTOR_MOV, Memory(4, null, 1, 12, 16), Register(19, 16))
        add(Operation.MOV, Memory(8, 9, 1, 4, 4), Immediate(61))
        add(Operation.SCALAR_MOV, Memory(8, 9, 1, 16, 8), Register(16, 8))
        add(Operation.MOV, Memory(8, 9, 1, 56, 4), Immediate(0))
        add(Operation.VECTOR_MOV, Register(21, 16), Memory(4, null, 1, 9, 16))
        add(Operation.VECTOR_MOV, Memory(8, 9, 1, 41, 16), Register(21, 16))
        add(Operation.VECTOR_MOV, Register(22, 16), Memory(4, null, 1, 0, 16))
        add(Operation.VECTOR_MOV, Memory(8, 9, 1, 32, 16), Register(22, 16))
        add(Operation.ADD, Register(4, 8), Immediate(64))
        add(Operation.POP, Register(13, 8))
        add(Operation.POP, Register(12, 8))
        add(Operation.POP, Register(5, 8))
        add(Operation.RET)
        return X64ControlFlow(code)
    }

    @Test
    fun preservesOverlappingCopiesAndKeepsOnlyWrittenDefaults() {
        val copy = MousePayloadCopy.analyze(fixture(), EventHeader(96, 4, 16), setOf(7))
        assertEquals(28, copy.extent)
        assertEquals((32L..56L).associateWith { it - 32 }, copy.bytes)
        assertEquals((57L..59L).associateWith { 0 }, copy.defaults)
        assertEquals(40L, copy.field(8, 4))
        assertFails { copy.field(24, 4) }
    }

    @Test
    fun rejectsForeignArgumentsStrideMismatchAndMissingCopyBytes() {
        val flow = fixture()
        for (changed in
            listOf(
                flow.body.getValue(5).copy(source = Register(1, 8)),
                flow.body.getValue(10).copy(immediate = 88),
                flow.body.getValue(18).copy(source = Memory(4, null, 1, 24, 16)),
            )) {
            val altered =
                X64ControlFlow(
                    flow.instructions.map { if (it.offset == changed.offset) changed else it }
                )
            assertFails { MousePayloadCopy.analyze(altered, EventHeader(96, 4, 16), setOf(7)) }
        }
    }
}
