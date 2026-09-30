package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PollEventCopiesTest {
    private val header = EventHeader(64, 4, 16)
    private fun fixture(): X64ControlFlow {
        val code = mutableListOf<Instruction>()
        fun add(operation: Operation, target: Operand? = null, source: Operand? = null, condition: Int? = null) {
            code += Instruction(code.size.toLong(), 1, operation, target, source, condition)
        }
        add(Operation.PUSH, Register(5, 8))
        add(Operation.MOV, Register(5, 8), Register(4, 8))
        add(Operation.PUSH, Register(3, 8))
        add(Operation.SUB, Register(4, 8), Immediate(72))
        add(Operation.MOV, Register(3, 8), Register(7, 8))
        add(Operation.MOV, Register(7, 8), Register(6, 8))
        add(Operation.LEA, Register(6, 8), Memory(5, null, 1, -72, 8))
        add(Operation.CALL, Immediate(1000))
        add(Operation.TEST, Register(0, 1), Register(0, 1))
        add(Operation.JCC, Immediate(29), condition = 4)
        add(Operation.MOV, Register(0, 4), Memory(5, null, 1, -68, 4))
        add(Operation.CMP, Register(0, 4), Immediate(64))
        add(Operation.JCC, Immediate(30), condition = 7)
        for (offset in listOf(0L, 16L, 32L, 48L)) {
            add(Operation.VECTOR_MOV, Register(16, 16), Memory(5, null, 1, -72 + offset, 16))
            add(Operation.VECTOR_MOV, Memory(3, null, 1, offset, 16), Register(16, 16))
        }
        add(Operation.MOV, Memory(3, null, 1, 64, 1), Immediate(1))
        add(Operation.ADD, Register(4, 8), Immediate(72))
        add(Operation.POP, Register(3, 8))
        add(Operation.POP, Register(5, 8))
        add(Operation.RET)
        // Keep branch targets explicit so malformed CFG and unsupported destructor paths remain separate failures.
        while (code.size < 29) add(Operation.NOP)
        add(Operation.RET)
        add(Operation.CALL, Immediate(2000))
        add(Operation.RET)
        return X64ControlFlow(code)
    }

    @Test
    fun preservesAllSelectedBytesAndDoesNotRunPayloadDestruction() {
        val result = PollEventCopies.analyze(fixture(), emptyList(), header, 7, setOf(3, 5))
        assertEquals(setOf(3L, 5L), result.keys)
        for (proof in result.values) {
            assertEquals((0L until 64L).toSet(), proof.bytes)
            assertEquals(64L, proof.engaged)
        }
        assertFails { PollEventCopies.analyze(fixture(), emptyList(), header, 7, setOf(99)) }
    }

    @Test
    fun rejectsChangedBytesBorrowedPointersUnknownBranchesAndLocalMutation() {
        val flow = fixture()
        fun rejects(at: Long, replacement: Instruction) {
            assertFails {
                PollEventCopies.analyze(X64ControlFlow(flow.instructions.map {
                    if (it.offset == at) replacement else it
                }), emptyList(), header, 7, setOf(3, 5))
            }
        }

        val load = flow.body.getValue(13)
        val source = load.source as Memory
        rejects(13, load.copy(source = source.copy(displacement = source.displacement + 1)))
        rejects(13, load.copy(source = source.copy(base = 7)))
        val store = flow.body.getValue(14)
        val target = store.destination as Memory
        rejects(14, store.copy(source = Immediate(0)))
        rejects(14, store.copy(destination = target.copy(base = 5, displacement = -72)))
        rejects(14, store.copy(source = Register(3, 8)))
        val kind = flow.body.getValue(10)
        rejects(10, kind.copy(source = (kind.source as Memory).copy(displacement = -32)))
        rejects(14, store.copy(operation = Operation.CALL, destination = Immediate(2000), source = null))
        val engaged = flow.body.getValue(21)
        rejects(21, engaged.copy(source = Immediate(0)))
        rejects(21, engaged.copy(destination = (engaged.destination as Memory).copy(displacement = 80)))
    }
}
