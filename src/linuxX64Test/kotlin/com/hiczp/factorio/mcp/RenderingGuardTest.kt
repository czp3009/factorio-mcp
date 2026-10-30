package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.*

class RenderingGuardTest {
    private fun body(register: Int = 3) = listOf(
        Instruction(0, 1, Operation.MOV, Register(register, 8), Register(7, 8)),
        Instruction(1, 1, Operation.CMP, Memory(null, null, 1, 998, 1, true), Immediate(0)),
        Instruction(2, 1, Operation.JCC, Immediate(14), condition = 5),
        Instruction(3, 1, Operation.CMP, Memory(register, null, 1, 16, 1), Immediate(0)),
        Instruction(4, 1, Operation.JCC, Immediate(14), condition = 5),
        Instruction(5, 1, Operation.CMP, Memory(register, null, 1, 24, 1), Immediate(1)),
        Instruction(6, 1, Operation.JCC, Immediate(18), condition = 5),
        Instruction(7, 1, Operation.MOV, Register(0, 8), Memory(null, null, 1, 1992, 8, true)),
        Instruction(8, 1, Operation.MOV, Register(0, 8), Memory(0, null, 1, 40, 8)),
        Instruction(9, 1, Operation.TEST, Register(0, 8), Register(0, 8)),
        Instruction(10, 1, Operation.JCC, Immediate(18), condition = 4),
        Instruction(11, 1, Operation.CMP, Memory(0, null, 1, 56, 1), Immediate(1)),
        Instruction(12, 1, Operation.JCC, Immediate(18), condition = 5),
        Instruction(13, 1, Operation.NOP),
        Instruction(14, 1, Operation.MOV, Register(7, 8), Memory(register, null, 1, 32, 8)),
        Instruction(15, 1, Operation.MOV, Register(0, 8), Memory(7, null, 1, 0, 8)),
        Instruction(16, 1, Operation.JMP, Memory(0, null, 1, 24, 8)),
    )

    private fun inspect(code: List<Instruction> = body(), globalSize: Long = 80, settingsSize: Long = 96) =
        RenderingGuard.analyze(code, 0, 2000, 1000, globalSize, settingsSize, 32, 3)

    @Test
    fun provesEveryBranchAcrossRegisterAndComparisonForms() {
        for (register in listOf(3, 12)) {
            assertEquals(RenderingGuard(24, 16, 40, 56), inspect(body(register)))
            val zeroComparison = body(register).map {
                when (it.offset) {
                    5L, 11L -> it.copy(source = Immediate(0))
                    6L, 12L -> it.copy(condition = 4)
                    else -> it
                }
            }
            assertEquals(RenderingGuard(24, 16, 40, 56), inspect(zeroComparison))
        }
    }

    @Test
    fun acceptsLoadedFlagsAndLoadedVirtualEntries() {
        val flags = body().flatMap { instruction ->
            val offset = instruction.offset * 2
            val flag = instruction.destination as? Memory
            if (instruction.operation == Operation.CMP && flag != null && !flag.relative) {
                val value = Register(10, 4)
                listOf(
                    Instruction(offset, 1, Operation.MOVZX, value, flag),
                    if (instruction.source == Immediate(0)) Instruction(offset + 1, 1, Operation.TEST, value, value)
                    else Instruction(offset + 1, 1, Operation.CMP, value, instruction.source),
                )
            } else {
                fun relocate(operand: Operand?): Operand? = when {
                    operand is Memory && operand.relative -> operand.copy(displacement =
                        operand.displacement + instruction.offset + instruction.size - offset - 2)
                    operand is Immediate && instruction.operation in setOf(Operation.JMP, Operation.JCC) ->
                        Immediate(operand.value * 2)
                    else -> operand
                }
                listOf(instruction.copy(offset = offset, size = 2,
                    destination = relocate(instruction.destination), source = relocate(instruction.source)))
            }
        }
        assertEquals(RenderingGuard(24, 16, 40, 56), inspect(flags))
        val entry = body().dropLast(1) + listOf(
            Instruction(16, 1, Operation.MOV, Register(10, 8), Memory(0, null, 1, 24, 8)),
            Instruction(17, 1, Operation.JMP, Register(10, 8)),
        )
        assertEquals(RenderingGuard(24, 16, 40, 56), inspect(entry))
        assertFails { inspect(entry.map { if (it.offset == 16L) it.copy(source = Memory(0, null, 1, 32, 8)) else it }) }
        assertFails { inspect(entry.map { if (it.offset == 16L) it.copy(destination = Register(10, 4)) else it }) }
    }

    @Test
    fun rejectsWrongGlobalWidthBoundsAndBranches() {
        fun changed(site: Long, change: (Instruction) -> Instruction) = body().map { if (it.offset == site) change(it) else it }
        assertFails { inspect(changed(7) { it.copy(source = Memory(null, null, 1, 2000, 8, true)) }) }
        assertFails { inspect(changed(8) { it.copy(source = Memory(0, null, 1, 40, 4)) }) }
        assertFails { inspect(changed(11) { it.copy(destination = Memory(0, null, 1, 56, 2)) }) }
        assertFails { inspect(changed(10) { it.copy(destination = Immediate(14)) }) }
        assertFails { inspect(changed(4) { it.copy(condition = 4) }) }
        assertFails { inspect(changed(16) { it.copy(destination = Memory(0, null, 1, 32, 8)) }) }
        assertFails { inspect(globalSize = 40) }
        assertFails { inspect(settingsSize = 56) }
        assertFails { inspect(changed(13) { it.copy(operation = Operation.CALL, destination = Immediate(1024)) }) }
        assertFails { inspect(changed(13) { it.copy(operation = Operation.CMP, destination = Register(8, 8), source = Immediate(0)) }) }
    }
}
