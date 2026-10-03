package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class GlobalPointerIterationTest {
    private fun body(first: Long, last: Long): List<Instruction> = buildList {
        fun op(kind: Operation, destination: Operand? = null, source: Operand? = null, condition: Int? = null) {
            add(Instruction(size.toLong(), 1, kind, destination, source, condition))
        }
        fun global(member: Long) = Memory(null, null, 1, 0x10000 + member - size - 1, 8, true)
        val cursor = Register(13, 8)
        val end = Register(2, 8)
        val saved = Memory(5, null, 1, -16, 8)
        op(Operation.PUSH, Register(5, 8))
        op(Operation.MOV, Register(5, 8), Register(4, 8))
        op(Operation.PUSH, cursor)
        op(Operation.SUB, Register(4, 8), Immediate(8))
        op(Operation.MOV, cursor, global(first))
        op(Operation.MOV, end, global(last))
        op(Operation.CMP, cursor, end)
        op(Operation.JCC, Immediate(15), condition = 4)
        op(Operation.MOV, saved, end)
        op(Operation.MOV, Register(0, 8), Memory(13, null, 1, 0, 8))
        op(Operation.CALL, Immediate(4096))
        op(Operation.MOV, end, saved)
        op(Operation.ADD, cursor, Immediate(8))
        op(Operation.CMP, cursor, end)
        op(Operation.JCC, Immediate(9), condition = 5)
        op(Operation.NOP)
        op(Operation.ADD, Register(4, 8), Immediate(8))
        op(Operation.POP, cursor)
        op(Operation.POP, Register(5, 8))
        op(Operation.RET)
    }

    @Test
    fun identifiesEndByIterationRatherThanMemberOrderAndRetainsPrivateSpills() {
        for ((first, last) in listOf(0L to 16L, 24L to 8L)) {
            assertEquals(last, GlobalPointerIteration.analyze(X64ControlFlow(body(first, last)), 0x10000, 0x20000, 32, first))
        }
    }

    @Test
    fun rejectsWrongStrideMissingGuardsLostEndAndEscapedSpills() {
        val code = body(0, 16)
        val changes = listOf(
            7 to code[7].copy(condition = 5),
            7 to code[7].copy(destination = Immediate(9)),
            9 to code[9].copy(source = Memory(13, null, 1, 0, 4), destination = Register(0, 4)),
            11 to code[11].copy(source = Memory(5, null, 1, -8, 8)),
            11 to code[11].copy(source = Immediate(0)),
            12 to code[12].copy(source = Immediate(16)),
            14 to code[14].copy(condition = 4),
            14 to code[14].copy(destination = Immediate(11)),
            8 to code[8].copy(source = Register(5, 8)),
        )
        for ((site, changed) in changes) assertFails("site=$site, changed=$changed") {
            GlobalPointerIteration.analyze(X64ControlFlow(code.map { if (it.offset == site.toLong()) changed else it }),
                0x10000, 0x20000, 32, 0)
        }
    }
}
