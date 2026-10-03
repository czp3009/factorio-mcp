package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LuaEnumNamesTest {
    private fun instructions(base: Int = 0, index: Int = 2, receiver: Int = 14): List<Instruction> =
        listOf(
            Instruction(0, 1, Operation.PUSH, Register(5, 8)),
            Instruction(1, 1, Operation.MOV, Register(3, 8), Register(6, 8)),
            Instruction(2, 1, Operation.MOV, Register(receiver, 8), Memory(7, null, 1, 24, 8)),
            Instruction(3, 1, Operation.CALL, Immediate(9000)),
            Instruction(
                4,
                1,
                Operation.MOVZX,
                Register(index, 4),
                Memory(receiver, null, 1, 48, 1),
            ),
            Instruction(5, 1, Operation.CMP, Register(index, 8), Immediate(3)),
            Instruction(6, 1, Operation.JCC, Immediate(13), condition = 3),
            Instruction(
                7,
                1,
                Operation.LEA,
                Register(base, 8),
                Memory(null, null, 1, 4088, 8, true),
            ),
            Instruction(8, 1, Operation.NOP),
            Instruction(9, 1, Operation.MOV, Register(6, 8), Memory(base, index, 8, 0, 8)),
            Instruction(10, 1, Operation.MOV, Register(7, 8), Register(3, 8)),
            Instruction(11, 1, Operation.CALL, Immediate(8000)),
            Instruction(12, 1, Operation.RET),
            Instruction(13, 1, Operation.JMP, Immediate(10000)),
        )

    private fun analyze(body: List<Instruction>) =
        LuaEnumNames.analyze(X64ControlFlow(body), 4096, 8000, 64, 128)

    @Test
    fun followsRegistersAndUnrelatedInstructionsWithoutCallOrdinals() {
        for (base in listOf(0, 10, 11)) for (index in listOf(1, 2, 8, 9)) for (receiver in
            listOf(12, 13, 14, 15)) {
            assertEquals(
                LuaEnumNames.Table(24, 48, 8192, 3),
                analyze(instructions(base, index, receiver)),
            )
        }
        val inclusive = instructions().toMutableList()
        inclusive[5] = inclusive[5].copy(source = Immediate(2))
        inclusive[6] = inclusive[6].copy(condition = 7)
        assertEquals(LuaEnumNames.Table(24, 48, 8192, 3), analyze(inclusive))
        val forwardedResult = instructions().toMutableList()
        forwardedResult[8] = Instruction(8, 1, Operation.MOV, Register(7, 8), Register(3, 8))
        forwardedResult[9] = forwardedResult[9].copy(destination = Register(8, 8))
        forwardedResult[10] = Instruction(10, 1, Operation.MOV, Register(6, 8), Register(8, 8))
        assertEquals(LuaEnumNames.Table(24, 48, 8192, 3), analyze(forwardedResult))
        val forwardedBase = instructions().toMutableList()
        forwardedBase[7] = forwardedBase[7].copy(destination = Register(8, 8))
        forwardedBase[8] = Instruction(8, 1, Operation.MOV, Register(0, 8), Register(8, 8))
        assertEquals(LuaEnumNames.Table(24, 48, 8192, 3), analyze(forwardedBase))
        forwardedBase[8] =
            forwardedBase[8].copy(destination = Register(0, 4), source = Register(8, 4))
        assertFails { analyze(forwardedBase) }
    }

    @Test
    fun rejectsMissingGuardBypassAndFailureReturningAResult() {
        for (condition in listOf(2, 4, 5, 12, 15)) {
            val body = instructions().toMutableList()
            body[6] = body[6].copy(condition = condition)
            assertFails { analyze(body) }
        }
        val bypass =
            instructions()
                .map { instruction ->
                    val target = instruction.destination as? Immediate
                    instruction.copy(
                        offset = instruction.offset + if (instruction.offset >= 5) 1 else 0,
                        destination =
                            if (
                                instruction.operation in listOf(Operation.JCC, Operation.JMP) &&
                                    target != null &&
                                    target.value in 5..13
                            )
                                Immediate(target.value + 1)
                            else instruction.destination,
                        source =
                            if (instruction.offset == 7L)
                                (instruction.source as Memory).copy(displacement = 4087)
                            else instruction.source,
                    )
                }
                .toMutableList()
        bypass.add(5, Instruction(5, 1, Operation.JCC, Immediate(8), condition = 4))
        assertFails { analyze(bypass) }
        val failure = instructions().toMutableList()
        failure[13] = Instruction(13, 1, Operation.RET)
        assertFails { analyze(failure) }
        val rejoins = instructions().toMutableList()
        rejoins[13] = Instruction(13, 1, Operation.JMP, Immediate(7))
        assertFails { analyze(rejoins) }
    }

    @Test
    fun rejectsChangedOrTruncatedIndexAndLostReceivers() {
        val changed = instructions().toMutableList()
        changed[8] = Instruction(8, 1, Operation.MOV, Register(2, 4), Immediate(0))
        assertFails { analyze(changed) }
        val truncated = instructions().toMutableList()
        truncated[4] = truncated[4].copy(destination = Register(2, 1), operation = Operation.MOV)
        assertFails { analyze(truncated) }
        val lost = instructions().toMutableList()
        lost[2] = lost[2].copy(destination = Register(14, 4))
        assertFails { analyze(lost) }
        val callerSaved = instructions(receiver = 10)
        assertFails { analyze(callerSaved) }
        val wrongLua = instructions().toMutableList()
        wrongLua[10] = wrongLua[10].copy(source = Register(14, 8))
        assertFails { analyze(wrongLua) }
    }

    @Test
    fun rejectsInvalidWidthsBoundsAndTableAddressing() {
        for ((site, operand) in
            listOf(
                2 to Memory(7, null, 1, 60, 8),
                4 to Memory(14, null, 1, 128, 1),
                4 to Memory(14, null, 1, 48, 4),
                9 to Memory(0, 2, 4, 0, 8),
                9 to Memory(0, 2, 8, 0, 4),
                9 to Memory(0, 2, 8, 8, 8),
                7 to Memory(null, null, 1, 4089, 8, true),
            )) {
            val body = instructions().toMutableList()
            body[site] = body[site].copy(source = operand)
            assertFails { analyze(body) }
        }
    }
}
