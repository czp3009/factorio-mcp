package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class NativeListNodeLayoutTest {
    private fun code(): List<Instruction> {
        val result = mutableListOf<Instruction>()
        fun add(
            op: Operation, target: X64Instructions.Operand? = null, source: X64Instructions.Operand? = null,
            condition: Int? = null
        ) {
            result += Instruction(result.size.toLong(), 1, op, target, source, condition)
        }

        fun reg(number: Int) = Register(number, 8)
        fun memory(base: Int, offset: Long = 0) = Memory(base, null, 1, offset, 8)
        add(Operation.PUSH, reg(3))
        add(Operation.PUSH, reg(14))
        add(Operation.PUSH, reg(15))
        add(Operation.MOV, reg(14), memory(7))
        add(Operation.CMP, reg(14), reg(7))
        add(Operation.JCC, Immediate(17), condition = 4)
        add(Operation.MOV, reg(3), reg(7))
        add(Operation.MOV, reg(15), memory(14))
        add(Operation.LEA, reg(7), memory(14, 24))
        add(Operation.CALL, Immediate(1000))
        add(Operation.MOV, Register(6, 4), Immediate(160))
        add(Operation.MOV, reg(7), reg(14))
        add(Operation.CALL, Immediate(1100))
        add(Operation.MOV, reg(14), reg(15))
        add(Operation.CMP, reg(15), reg(3))
        add(Operation.JCC, Immediate(7), condition = 5)
        add(Operation.NOP)
        add(Operation.POP, reg(15))
        add(Operation.POP, reg(14))
        add(Operation.POP, reg(3))
        add(Operation.RET)
        return result
    }

    @Test
    fun derivesValueAndAllocationWithEmptyAndNonemptyPaths() {
        assertEquals(NativeListNodeLayout(0, 0, 24, 160), NativeListNodeLayout.analyze(code(), 0, 1000, 1100))
    }

    @Test
    fun rejectsWrongOwnershipBoundsLoopAndFrame() {
        fun changed(index: Int, change: (Instruction) -> Instruction) = code().mapIndexed { n, instruction ->
            if (n == index) change(instruction) else instruction
        }
        for (body in listOf(
            changed(8) { it.copy(source = Memory(3, null, 1, 24, 8)) },
            changed(8) { it.copy(source = Memory(14, null, 1, 160, 8)) },
            changed(10) { it.copy(source = Immediate(16)) },
            changed(11) { it.copy(source = Register(15, 8)) },
            changed(12) { it.copy(destination = Immediate(1200)) },
            changed(13) { it.copy(source = Register(3, 8)) },
            changed(14) { it.copy(source = Register(14, 8)) },
            changed(15) { it.copy(destination = Immediate(8)) },
            changed(15) { it.copy(condition = 4) },
            changed(17) { it.copy(destination = Register(3, 8)) },
            changed(7) { it.copy(source = Memory(14, null, 1, 0, 4)) },
        )) assertFails { NativeListNodeLayout.analyze(body, 0, 1000, 1100) }
    }

    @Test
    fun rejectsADeletedNodeAliasRetainedAcrossIterations() {
        val instructions = mutableListOf<Instruction>()
        val mapped = mutableMapOf<Long, Long>()
        fun append(instruction: Instruction) {
            instructions += instruction.copy(offset = instructions.size.toLong())
        }
        append(Instruction(0, 1, Operation.PUSH, Register(12, 8)))
        append(Instruction(0, 1, Operation.PUSH, Register(13, 8)))
        for (instruction in code()) {
            if (instruction.offset == 7L) append(Instruction(0, 1, Operation.MOV, Register(12, 8), Register(14, 8)))
            mapped[instruction.offset] = instructions.size.toLong()
            if (instruction.operation == Operation.RET) {
                append(Instruction(0, 1, Operation.POP, Register(13, 8)))
                append(Instruction(0, 1, Operation.POP, Register(12, 8)))
            }
            append(if (instruction.offset == 8L) instruction.copy(source = Memory(12, null, 1, 24, 8)) else instruction)
        }
        val stale = instructions.map { instruction ->
            if (instruction.operation == Operation.JCC) instruction.copy(
                destination =
                    Immediate(mapped.getValue((instruction.destination as Immediate).value))
            ) else instruction
        }
        assertFails { NativeListNodeLayout.analyze(stale, 0, 1000, 1100) }
        val fresh = stale.map { instruction ->
            if (instruction.operation == Operation.LEA) instruction.copy(
                source = Memory(
                    14,
                    null,
                    1,
                    24,
                    8
                )
            ) else instruction
        }
        assertEquals(NativeListNodeLayout(0, 0, 24, 160), NativeListNodeLayout.analyze(fresh, 0, 1000, 1100))
    }
}
