package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertFails

class ChatStringCopyTest {
    private val string = NativeStringLayout(0, 8, 16, 32, ElfImage.Symbol("fixture_destroy", 0x3000, 1, 2, 1))
    private fun fixture(): List<Instruction> {
        val result = mutableListOf<Instruction>()
        fun emit(op: Operation, to: Operand? = null, from: Operand? = null, condition: Int? = null) {
            result += Instruction(result.size.toLong(), 1, op, to, from, condition)
        }

        fun reg(number: Int) = Register(number, 8)
        fun field(base: Int, offset: Long) = Memory(base, null, 1, offset, 8)
        emit(Operation.PUSH, reg(3))
        emit(Operation.MOV, reg(3), reg(7))
        emit(Operation.LEA, reg(12), field(3, 32))
        emit(Operation.MOV, field(3, 16), reg(12))
        emit(Operation.MOV, reg(13), field(2, 0))
        emit(Operation.MOV, reg(14), field(2, 8))
        emit(Operation.CMP, reg(14), Immediate(16))
        emit(Operation.JCC, Immediate(14), condition = 2)
        emit(Operation.MOV, reg(7), reg(14))
        emit(Operation.INC, reg(7))
        emit(Operation.CALL, Immediate(0x1000))
        emit(Operation.MOV, reg(12), reg(0))
        emit(Operation.MOV, field(3, 16), reg(0))
        emit(Operation.MOV, field(3, 32), reg(14))
        emit(Operation.MOV, reg(7), reg(12))
        emit(Operation.MOV, reg(6), reg(13))
        emit(Operation.MOV, reg(2), reg(14))
        emit(Operation.CALL, Immediate(0x2000))
        emit(Operation.MOV, field(3, 24), reg(14))
        emit(Operation.MOV, Memory(12, 14, 1, 0, 1), Immediate(0))
        emit(Operation.POP, reg(3))
        emit(Operation.RET)
        return result
    }

    private fun verify(instructions: List<Instruction>) {
        ChatStringCopy.analyze(X64ControlFlow(instructions), 96, 16, string, 0x1000, 0x2000)
    }

    @Test
    fun verifiesEveryBoundedLengthAcrossInlineAndHeapCopies() = verify(fixture())

    @Test
    fun rejectsNormalReturnBypassingPayloadConstruction() {
        val original = fixture()
        val bypass = listOf(Instruction(0, 1, Operation.JCC, Immediate(original.size.toLong()), condition = 4)) +
                original.map { instruction ->
                    instruction.copy(
                        offset = instruction.offset + 1, destination =
                            if (instruction.operation in listOf(Operation.JCC, Operation.JMP))
                                Immediate((instruction.destination as Immediate).value + 1) else instruction.destination
                    )
                }
        assertFails { verify(bypass) }
    }

    @Test
    fun rejectsBorrowedStorageWrongCapacityCopyLengthAndTermination() {
        val original = fixture()
        val invalid = listOf(
            3 to original[3].copy(source = Register(2, 8)),
            6 to original[6].copy(source = Immediate(32)),
            9 to original[9].copy(operation = Operation.NOP),
            13 to original[13].copy(source = Immediate(0)),
            16 to original[16].copy(source = Immediate(0)),
            16 to original[16].copy(operation = Operation.MOVZX, source = Register(14, 1)),
            17 to original[17].copy(destination = Immediate(0x2001)),
            18 to original[18].copy(source = Immediate(0)),
            19 to original[19].copy(destination = Memory(12, 14, 1, 1, 1)),
            19 to original[19].copy(source = Immediate(1)),
        )
        for ((index, replacement) in invalid) assertFails {
            verify(original.toMutableList().also { it[index] = replacement })
        }
    }
}
