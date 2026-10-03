package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ReturnedEventDefaultsTest {
    private val header = EventHeader(64, 0, 8)
    private val table = X64JumpTables.Table(6, 2, Register(1, 4), 5000, listOf(7, 12))
    private fun fixture() = X64ControlFlow(listOf(
        Instruction(0, 1, Operation.MOV, Register(1, 4), Memory(2, null, 1, 0, 4)),
        Instruction(1, 1, Operation.CMP, Register(1, 4), Immediate(1)),
        Instruction(2, 1, Operation.JCC, Immediate(12), condition = 7),
        Instruction(3, 1, Operation.LEA, Register(11, 8), Memory(null, null, 1, 5000, 8, true)),
        Instruction(4, 1, Operation.MOVSX, Register(10, 8), Memory(11, 1, 4, 0, 4)),
        Instruction(5, 1, Operation.ADD, Register(10, 8), Register(11, 8)),
        Instruction(6, 1, Operation.JMP, Register(10, 8)),
        Instruction(7, 1, Operation.VECTOR_XOR, Register(20, 16), Register(20, 16)),
        Instruction(8, 1, Operation.VECTOR_MOV, Memory(0, null, 1, 24, 16), Register(20, 16)),
        Instruction(9, 1, Operation.MOV, Register(12, 4), Immediate(-1)),
        Instruction(10, 1, Operation.MOV, Memory(0, null, 1, 44, 8), Register(12, 8)),
        Instruction(11, 1, Operation.JMP, Immediate(12)),
        Instruction(12, 1, Operation.RET),
    ), listOf(table))

    @Test
    fun derivesOverlappingVectorDefaultsAndZeroExtendedScalarConstants() {
        assertEquals((24L until 40L).associateWith { 0 } + (44L until 48L).associateWith { 255 } +
                (48L until 52L).associateWith { 0 }, ReturnedEventDefaults.analyze(fixture(), table, header, 0))
        assertEquals(emptyMap(), ReturnedEventDefaults.analyze(fixture(), table, header, 1))
    }

    @Test
    fun rejectsWrongArgumentUnknownPayloadOutOfBoundsAndChangedReturnedIdentity() {
        val flow = fixture()
        for ((site, change) in listOf(
            0L to flow.body.getValue(0).copy(source = Memory(6, null, 1, 0, 4)),
            7L to flow.body.getValue(7).copy(source = Register(21, 16)),
            8L to flow.body.getValue(8).copy(destination = Memory(0, null, 1, 56, 16)),
            8L to flow.body.getValue(8).copy(destination = Memory(7, null, 1, 24, 16)),
            9L to flow.body.getValue(9).copy(destination = Register(0, 4)),
            10L to flow.body.getValue(10).copy(destination = Memory(0, null, 1, 8, 8)),
        )) {
            val changed = X64ControlFlow(flow.instructions.map { if (it.offset == site) change else it }, listOf(table))
            assertFails { ReturnedEventDefaults.analyze(changed, table, header, 0) }
        }
    }
}
