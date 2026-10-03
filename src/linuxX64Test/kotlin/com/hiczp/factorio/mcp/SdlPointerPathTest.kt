package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SdlPointerPathTest {
    private val sdkType = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(2), 4)
    private fun fixture(): X64ControlFlow = X64ControlFlow(listOf(
        Instruction(0, 1, Operation.PUSH, Register(5, 8)),
        Instruction(1, 1, Operation.MOV, Register(5, 8), Register(4, 8)),
        Instruction(2, 1, Operation.SUB, Register(4, 8), Immediate(32)),
        Instruction(3, 1, Operation.MOV, Register(13, 8), Register(1, 8)),
        Instruction(4, 1, Operation.CMP, Memory(2, null, 1, 0, 4), Immediate(0x403)),
        Instruction(5, 1, Operation.JCC, Immediate(14), condition = 5),
        Instruction(6, 1, Operation.MOV, Memory(4, null, 1, 0, 4), Immediate(37)),
        Instruction(7, 1, Operation.MOV, Memory(4, null, 1, 8, 8), Immediate(0)),
        Instruction(8, 1, Operation.LEA, Register(6, 8), Memory(4, null, 1, 8, 8)),
        Instruction(9, 1, Operation.LEA, Register(2, 8), Memory(4, null, 1, 0, 8)),
        Instruction(10, 1, Operation.MOV, Register(7, 8), Register(13, 8)),
        Instruction(11, 1, Operation.NOP),
        Instruction(12, 1, Operation.CALL, Immediate(1000)),
        Instruction(13, 1, Operation.JMP, Immediate(14)),
        Instruction(14, 1, Operation.ADD, Register(4, 8), Immediate(32)),
        Instruction(15, 1, Operation.POP, Register(5, 8)),
        Instruction(16, 1, Operation.RET),
    ))

    @Test
    fun derivesKindAtTypedEmplaceFromTheSelectedSdkEventPath() {
        val proof = SdlPointerPath.construction(fixture(), emptyList(), 1000, 44, mapOf(sdkType to 0x403))
        assertEquals(37L, proof.kind)
        assertEquals(12L, proof.call)
        val saved = X64ControlFlow(fixture().instructions.map {
            when (it.offset) {
                3L -> it.copy(destination = Memory(4, null, 1, 16, 8))
                10L -> it.copy(source = Memory(4, null, 1, 16, 8))
                else -> it
            }
        })
        assertEquals(37L, SdlPointerPath.construction(saved, emptyList(), 1000, 44, mapOf(sdkType to 0x403)).kind)
    }

    @Test
    fun rejectsWrongSdkEventQueueBorrowAndInterveningCall() {
        val flow = fixture()
        assertFails { SdlPointerPath.construction(flow, emptyList(), 1000, 44, mapOf(sdkType to 0x402)) }
        for ((site, change) in listOf(
            10L to flow.body.getValue(10).copy(source = Register(12, 8)),
            9L to flow.body.getValue(9).copy(source = Memory(4, null, 1, 32, 8)),
            11L to flow.body.getValue(11).copy(operation = Operation.CALL, destination = Immediate(2000)),
            6L to flow.body.getValue(6).copy(source = Register(0, 4)),
        )) {
            val changed = X64ControlFlow(flow.instructions.map { if (it.offset == site) change else it })
            assertFails { SdlPointerPath.construction(changed, emptyList(), 1000, 44, mapOf(sdkType to 0x403)) }
        }
    }

    @Test
    fun followsAnUnrelatedSwitchOverflowBeforeSelectingTheMouseConstructor() {
        val table = X64JumpTables.Table(10, 6, Register(1, 4), 5000, listOf(11, 11))
        val flow = X64ControlFlow(listOf(
            Instruction(0, 1, Operation.PUSH, Register(5, 8)),
            Instruction(1, 1, Operation.MOV, Register(5, 8), Register(4, 8)),
            Instruction(2, 1, Operation.SUB, Register(4, 8), Immediate(32)),
            Instruction(3, 1, Operation.MOV, Register(13, 8), Register(1, 8)),
            Instruction(4, 1, Operation.MOV, Register(1, 4), Memory(2, null, 1, 0, 4)),
            Instruction(5, 1, Operation.CMP, Register(1, 4), Immediate(1)),
            Instruction(6, 1, Operation.JCC, Immediate(12), condition = 7),
            Instruction(7, 1, Operation.LEA, Register(11, 8), Memory(null, null, 1, 5000, 8, true)),
            Instruction(8, 1, Operation.MOVSX, Register(10, 8), Memory(11, 1, 4, 0, 4)),
            Instruction(9, 1, Operation.ADD, Register(10, 8), Register(11, 8)),
            Instruction(10, 1, Operation.JMP, Register(10, 8)),
            Instruction(11, 1, Operation.RET),
            Instruction(12, 1, Operation.MOV, Memory(4, null, 1, 0, 4), Immediate(57)),
            Instruction(13, 1, Operation.MOV, Memory(4, null, 1, 8, 8), Immediate(0)),
            Instruction(14, 1, Operation.LEA, Register(6, 8), Memory(4, null, 1, 8, 8)),
            Instruction(15, 1, Operation.LEA, Register(2, 8), Memory(4, null, 1, 0, 8)),
            Instruction(16, 1, Operation.MOV, Register(7, 8), Register(13, 8)),
            Instruction(17, 1, Operation.CALL, Immediate(1000)),
            Instruction(18, 1, Operation.ADD, Register(4, 8), Immediate(32)),
            Instruction(19, 1, Operation.POP, Register(5, 8)),
            Instruction(20, 1, Operation.RET),
        ), listOf(table))
        assertEquals(57L, SdlPointerPath.construction(flow, listOf(table), 1000, 44, mapOf(sdkType to 0x403)).kind)
        val selectedTable = table.copy(targets = listOf(12, 12))
        val selected = X64ControlFlow(flow.instructions, listOf(selectedTable))
        assertEquals(57L, SdlPointerPath.construction(selected, listOf(selectedTable), 1000, 44, mapOf(sdkType to 1)).kind)
        val clobbered = X64ControlFlow(flow.instructions.map {
            when (it.offset) {
                7L -> it.copy(destination = Register(13, 8))
                8L -> it.copy(source = Memory(13, 1, 4, 0, 4))
                9L -> it.copy(source = Register(13, 8))
                else -> it
            }
        }, listOf(selectedTable))
        assertFails { SdlPointerPath.construction(clobbered, listOf(selectedTable), 1000, 44, mapOf(sdkType to 1)) }
    }
}
