package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChatRecordPlayerNameTest {
    private fun code(): List<Instruction> =
        listOf(
            Instruction(0, 1, Operation.MOV, Register(3, 8), Register(6, 8)),
            Instruction(1, 1, Operation.LEA, Register(6, 8), Memory(7, null, 1, 16, 8)),
            Instruction(2, 1, Operation.MOV, Register(7, 8), Register(3, 8)),
            Instruction(3, 1, Operation.CALL, Immediate(1000)),
            Instruction(4, 1, Operation.RET),
        )

    @Test
    fun associatesTheRetainedStringWithItsOriginalRecordAndSerializer() {
        assertEquals(16, ChatRecordPlayerName.analyze(X64ControlFlow(code()), 1000, 80, 32))
    }

    @Test
    fun rejectsPointerLoadsWrongOwnersTruncationAndOutOfBoundsStrings() {
        fun changed(index: Int, change: (Instruction) -> Instruction) =
            code().mapIndexed { n, instruction ->
                if (n == index) change(instruction) else instruction
            }
        for (body in
            listOf(
                changed(1) { it.copy(operation = Operation.MOV) },
                changed(1) { it.copy(source = Memory(6, null, 1, 16, 8)) },
                changed(1) { it.copy(source = Memory(7, null, 1, 56, 8)) },
                changed(1) { it.copy(source = Memory(7, null, 1, 17, 8)) },
                changed(1) { it.copy(destination = Register(6, 4)) },
                changed(2) { it.copy(source = Register(2, 8)) },
                changed(3) { it.copy(destination = Immediate(2000)) },
                changed(4) { it.copy(operation = Operation.CALL, destination = Immediate(1000)) } +
                    Instruction(5, 1, Operation.RET),
            )) assertFails { ChatRecordPlayerName.analyze(X64ControlFlow(body), 1000, 80, 32) }
        assertFails { ChatRecordPlayerName.analyze(X64ControlFlow(code()), 1000, 40, 32) }
    }
}
