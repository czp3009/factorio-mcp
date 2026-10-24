package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class KeyboardEventPayloadTest {
    private val header = EventHeader(64, 4, 16)

    private fun fixture(): X64ControlFlow {
        val code = mutableListOf<Instruction>()
        fun add(
            operation: Operation,
            target: Operand? = null,
            source: Operand? = null,
            condition: Int? = null,
        ) {
            code += Instruction(code.size.toLong(), 1, operation, target, source, condition)
        }
        fun output(offset: Long, width: Int) = Memory(2, 0, 1, offset, width)
        add(Operation.MOV, Register(12, 8), Register(6, 8))
        add(Operation.MOV, Register(13, 8), Register(7, 8))
        add(Operation.MOV, Register(7, 8), Register(12, 8))
        add(Operation.CALL, Immediate(1000))
        add(Operation.MOV, Register(0, 4), Memory(12, null, 1, 8, 4))
        add(Operation.SHL, Register(0, 8), Immediate(6))
        add(Operation.MOV, Register(2, 8), Memory(12, null, 1, 0, 8))
        add(Operation.MOV, Register(1, 4), Memory(13, null, 1, 24, 4))
        add(Operation.MOVZX, Register(15, 4), Memory(13, null, 1, 32, 2))
        add(Operation.SHL, Register(15, 8), Immediate(32))
        add(Operation.OR, Register(15, 8), Register(1, 8))
        add(Operation.MOVZX, Register(11, 4), Memory(13, null, 1, 37, 1))
        add(Operation.SHL, Register(11, 8), Immediate(32))
        add(Operation.MOV, Register(8, 4), Memory(13, null, 1, 0, 4))
        add(Operation.XOR, Register(14, 4), Register(14, 4))
        add(Operation.CMP, Register(8, 4), Immediate(0x301))
        add(Operation.SET, Register(14, 1), condition = 4)
        add(Operation.ADD, Register(14, 4), Register(14, 4))
        add(Operation.MOV, output(4, 4), Register(14, 4))
        add(Operation.SCALAR_MOV, output(16, 8), Register(16, 8))
        add(Operation.MOV, output(56, 8), Immediate(0))
        add(Operation.MOV, output(40, 8), Register(15, 8))
        add(Operation.MOV, Register(10, 8), Register(11, 8))
        add(Operation.SHR, Register(10, 8), Immediate(32))
        add(Operation.MOV, output(60, 1), Register(10, 1))
        add(Operation.MOV, output(56, 4), Register(11, 4))
        add(Operation.RET)
        return X64ControlFlow(code)
    }

    @Test
    fun derivesPackedModifiersAndNonrepeatDefaults() {
        val flow = fixture()
        val result = KeyboardEventPayload.analyze(flow, header, 40, 0, 2, setOf(3))
        assertEquals(44L, result.modifiers)
        assertEquals(
            (46L..47L).associateWith { 0 } + (56L..63L).associateWith { 0 },
            result.defaults,
        )
        val multiply =
            flow.body
                .getValue(5)
                .copy(
                    operation = Operation.MULTIPLY_IMMEDIATE,
                    source = Register(0, 8),
                    immediate = 64,
                )
        assertEquals(
            result,
            KeyboardEventPayload.analyze(
                X64ControlFlow(flow.instructions.map { if (it.offset == 5L) multiply else it }),
                header,
                40,
                0,
                2,
                setOf(3),
            ),
        )
    }

    @Test
    fun rejectsAnUnrelatedConstantEventKindAsACandidate() {
        val flow = fixture()
        val unrelated =
            X64ControlFlow(
                flow.instructions.map {
                    if (it.offset == 18L) it.copy(source = Immediate(7)) else it
                }
            )
        assertFailsWith<IllegalStateException> {
            KeyboardEventPayload.analyze(unrelated, header, 40, 0, 2, setOf(3))
        }
    }

    @Test
    fun rejectsChangedStrideSdkFieldsAndPackedProvenance() {
        val flow = fixture()
        fun rejects(at: Long, replacement: Instruction) {
            assertFails {
                KeyboardEventPayload.analyze(
                    X64ControlFlow(
                        flow.instructions.map { if (it.offset == at) replacement else it }
                    ),
                    header,
                    40,
                    0,
                    2,
                    setOf(3),
                )
            }
        }
        rejects(5, flow.body.getValue(5).copy(source = Immediate(5)))
        rejects(2, flow.body.getValue(2).copy(source = Register(13, 8)))
        rejects(8, flow.body.getValue(8).copy(source = Memory(13, null, 1, 30, 2)))
        rejects(8, flow.body.getValue(8).copy(source = Memory(13, null, 1, 32, 4)))
        rejects(9, flow.body.getValue(9).copy(source = Immediate(31)))
        rejects(
            7,
            flow.body
                .getValue(7)
                .copy(destination = Register(1, 8), source = Memory(13, null, 1, 24, 8)),
        )
        rejects(11, flow.body.getValue(11).copy(source = Memory(13, null, 1, 13, 1)))
        rejects(15, flow.body.getValue(15).copy(source = Immediate(0x302)))
        rejects(24, flow.body.getValue(24).copy(destination = Memory(2, 0, 1, 72, 1)))
        rejects(24, flow.body.getValue(24).copy(source = Register(15, 1)))
        assertFails { KeyboardEventPayload.analyze(flow, header, 40, 2, 0, setOf(3)) }
    }
}
