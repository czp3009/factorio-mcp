package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PointerInputStateTest {
    private val header = EventHeader(64, 4, 16)
    private val motion = PointerEventPayloads.Case(PointerEventPayloads.OperationKind.MOVE, 1, 32, 36, defaults = emptyMap())
    private val enter = PointerEventPayloads.Case(PointerEventPayloads.OperationKind.ENTER, 2, defaults = emptyMap())
    private val table = X64JumpTables.Table(6, 2, Register(1, 4), 5000, listOf(15, 7, 11, 13))
    private fun fixture() = X64ControlFlow(listOf(
        Instruction(0, 1, Operation.MOV, Register(1, 4), Memory(6, null, 1, 4, 4)),
        Instruction(1, 1, Operation.CMP, Register(1, 4), Immediate(3)),
        Instruction(2, 1, Operation.JCC, Immediate(15), condition = 7),
        Instruction(3, 1, Operation.LEA, Register(11, 8), Memory(null, null, 1, 5000, 8, true)),
        Instruction(4, 1, Operation.MOVSX, Register(10, 8), Memory(11, 1, 4, 0, 4)),
        Instruction(5, 1, Operation.ADD, Register(10, 8), Register(11, 8)),
        Instruction(6, 1, Operation.JMP, Register(10, 8)),
        Instruction(7, 1, Operation.MOV, Register(0, 8), Memory(6, null, 1, 32, 8)),
        Instruction(8, 1, Operation.NOP),
        Instruction(9, 1, Operation.MOV, Memory(7, null, 1, 40, 8), Register(0, 8)),
        Instruction(10, 1, Operation.JMP, Immediate(15)),
        Instruction(11, 1, Operation.MOV, Memory(7, null, 1, 120, 1), Immediate(1)),
        Instruction(12, 1, Operation.JMP, Immediate(15)),
        Instruction(13, 1, Operation.MOV, Memory(7, null, 1, 120, 1), Immediate(0)),
        Instruction(14, 1, Operation.JMP, Immediate(15)),
        Instruction(15, 1, Operation.RET),
    ), listOf(table))

    @Test
    fun derivesCopiedCursorAndPairedWindowFlagUpdatesFromNativeKinds() {
        assertEquals(PointerInputState(40, 120), PointerInputState.analyze(fixture(), table, 160, header, motion, enter, 3))
    }

    @Test
    fun rejectsWrongPositionArgumentWidthWindowPairAndStateBounds() {
        val flow = fixture()
        for ((site, change) in listOf(
            7L to flow.body.getValue(7).copy(source = Memory(2, null, 1, 32, 8)),
            9L to flow.body.getValue(9).copy(source = Register(2, 8)),
            9L to flow.body.getValue(9).copy(destination = Memory(7, null, 1, 40, 4)),
            11L to flow.body.getValue(11).copy(source = Immediate(2)),
            13L to flow.body.getValue(13).copy(destination = Memory(7, null, 1, 119, 1)),
            13L to flow.body.getValue(13).copy(source = Immediate(1)),
        )) {
            val changed = X64ControlFlow(flow.instructions.map { if (it.offset == site) change else it }, listOf(table))
            assertFails { PointerInputState.analyze(changed, table, 160, header, motion, enter, 3) }
        }
        assertFails { PointerInputState.analyze(flow, table, 44, header, motion, enter, 3) }
        assertFails { PointerInputState.analyze(flow, table, 160, header, motion.copy(kind = 0), enter, 3) }
        val clobbered = X64ControlFlow(flow.instructions.map {
            when (it.offset) {
                3L -> it.copy(destination = Register(7, 8))
                4L -> it.copy(source = Memory(7, 1, 4, 0, 4))
                5L -> it.copy(source = Register(7, 8))
                else -> it
            }
        }, listOf(table))
        assertFails { PointerInputState.analyze(clobbered, table, 160, header, motion, enter, 3) }
    }
}
