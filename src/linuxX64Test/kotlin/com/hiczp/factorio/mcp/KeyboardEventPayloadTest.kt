package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class KeyboardEventPayloadTest {
    private val header = EventHeader(64, 4, 16)
    private fun fixture(): X64ControlFlow {
        val code = mutableListOf<Instruction>()
        fun add(operation: Operation, target: Operand? = null, source: Operand? = null, condition: Int? = null) {
            code += Instruction(code.size.toLong(), 1, operation, target, source, condition)
        }

        fun output(offset: Long, width: Int) = Memory(2, 0, 1, offset, width)
        add(Operation.MOV, Register(12, 8), Register(1, 8))
        add(Operation.MOV, Register(13, 8), Register(2, 8))
        add(Operation.MOV, Register(7, 8), Register(12, 8))
        add(Operation.CALL, Immediate(1000))
        add(Operation.MOV, Register(0, 4), Memory(12, null, 1, 8, 4))
        add(Operation.SHL, Register(0, 8), Immediate(6))
        add(Operation.MOV, Register(2, 8), Memory(12, null, 1, 0, 8))
        add(Operation.MOV, Register(15, 4), Memory(13, null, 1, 20, 4))
        add(Operation.CMP, Memory(13, null, 1, 13, 1), Immediate(0))
        add(Operation.SET, Register(3, 1), condition = 5)
        add(Operation.MOV, Register(1, 4), Memory(13, null, 1, 0, 4))
        add(Operation.XOR, Register(14, 4), Register(14, 4))
        add(Operation.CMP, Register(1, 4), Immediate(0x301))
        add(Operation.SET, Register(14, 1), condition = 4)
        add(Operation.ADD, Register(14, 4), Register(14, 4))
        add(Operation.MOV, Register(1, 4), Register(15, 4))
        add(Operation.MOV, output(4, 4), Register(14, 4))
        add(Operation.SCALAR_MOV, output(16, 8), Register(16, 8))
        add(Operation.MOV, output(56, 4), Immediate(0))
        add(Operation.MOV, output(40, 8), Register(1, 8))
        add(Operation.MOV, output(56, 1), Register(3, 1))
        add(Operation.RET)
        return X64ControlFlow(code)
    }

    @Test
    fun derivesTheNonrepeatScalarDefaultsFromTheTypedQueueElement() {
        val result = KeyboardEventPayload.analyze(fixture(), header, 40, 0, 2, setOf(3))
        assertEquals((44L..47L).associateWith { 0 } + (56L..59L).associateWith { 0 }, result.defaults)
    }

    @Test
    fun rejectsChangedStrideQueueKindAndPayloadSources() {
        val flow = fixture()
        fun rejects(at: Long, replacement: Instruction) {
            assertFails {
                KeyboardEventPayload.analyze(X64ControlFlow(flow.instructions.map {
                    if (it.offset == at) replacement else it
                }), header, 40, 0, 2, setOf(3))
            }
        }
        rejects(5, flow.body.getValue(5).copy(source = Immediate(5)))
        rejects(2, flow.body.getValue(2).copy(source = Register(13, 8)))
        rejects(12, flow.body.getValue(12).copy(source = Immediate(0x302)))
        rejects(8, flow.body.getValue(8).copy(destination = Memory(13, null, 1, 12, 1)))
        rejects(15, flow.body.getValue(15).copy(destination = Register(1, 8), source = Register(15, 8)))
        rejects(20, flow.body.getValue(20).copy(destination = Memory(2, 0, 1, 72, 1)))
        rejects(20, flow.body.getValue(20).copy(source = Register(15, 1)))
        rejects(18, flow.body.getValue(18).copy(destination = Memory(12, null, 1, 56, 4)))
        assertFails { KeyboardEventPayload.analyze(flow, header, 40, 2, 0, setOf(3)) }
    }
}
