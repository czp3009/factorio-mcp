package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChatRecordTextTest {
    private fun code(): List<Instruction> = listOf(
        Instruction(0, 1, Operation.TEST, Register(2, 8), Register(2, 8)),
        Instruction(1, 1, Operation.JCC, Immediate(4), condition = 4),
        Instruction(2, 1, Operation.LEA, Register(7, 8), Memory(7, null, 1, 24, 8)),
        Instruction(3, 1, Operation.JMP, Immediate(5)),
        Instruction(4, 1, Operation.LEA, Register(7, 8), Memory(7, null, 1, 24, 8)),
        Instruction(5, 1, Operation.CALL, Immediate(1000)),
        Instruction(6, 1, Operation.RET),
    )

    @Test
    fun requiresEveryPathToForwardTheSameMemberAndSerializer() {
        assertEquals(24, ChatRecordText.analyze(X64ControlFlow(code()), 1000, 80))
    }

    @Test
    fun rejectsPointerLoadsDisagreementWrongArgumentsAndBounds() {
        fun changed(index: Int, change: (Instruction) -> Instruction) = code().mapIndexed { n, instruction ->
            if (n == index) change(instruction) else instruction
        }
        for (body in listOf(
            changed(4) { it.copy(source = Memory(7, null, 1, 32, 8)) },
            changed(4) { it.copy(operation = Operation.MOV) },
            changed(4) { it.copy(source = Memory(6, null, 1, 24, 8)) },
            changed(4) { it.copy(destination = Register(7, 4)) },
            changed(2) { it.copy(destination = Register(6, 8)) },
            changed(5) { it.copy(destination = Immediate(2000)) },
            changed(3) { it.copy(operation = Operation.CALL, destination = Immediate(1000)) },
        )) assertFails { ChatRecordText.analyze(X64ControlFlow(body), 1000, 80) }
        assertFails { ChatRecordText.analyze(X64ControlFlow(code()), 1000, 24) }
    }
}
