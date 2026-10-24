package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.*

class KeyEventReadsTest {
    @Test
    fun followsHoistedHotkeyLoadsAndNativeCharacterArguments() {
        fun body(shift: Long = 0) =
            listOf(
                Instruction(0, 1, Operation.MOV, Register(14, 8), Register(6, 8)),
                Instruction(
                    1,
                    1,
                    Operation.MOV,
                    Register(1, 4),
                    Memory(14, null, 1, 20 + shift, 4),
                ),
                Instruction(
                    2,
                    1,
                    Operation.MOVZX,
                    Register(0, 4),
                    Memory(14, null, 1, 30 + shift, 1),
                ),
                Instruction(3, 1, Operation.CMP, Register(0, 1), Immediate(1)),
                Instruction(4, 1, Operation.JCC, Immediate(7), condition = 5),
                Instruction(5, 1, Operation.SUB, Register(1, 4), Immediate(97)),
                Instruction(6, 1, Operation.CMP, Register(1, 4), Immediate(25)),
                Instruction(
                    7,
                    1,
                    Operation.MOV,
                    Register(2, 4),
                    Memory(14, null, 1, 16 + shift, 4),
                ),
                Instruction(8, 1, Operation.NOP),
                Instruction(9, 1, Operation.JCC, Immediate(12), condition = 4),
                Instruction(10, 1, Operation.MOV, Register(6, 4), Memory(14, null, 1, shift, 4)),
                Instruction(11, 1, Operation.JMP, Immediate(13)),
                Instruction(12, 1, Operation.MOV, Register(6, 4), Immediate(10)),
                Instruction(13, 1, Operation.JMP, Immediate(0x2000)),
            )
        fun analyze(body: List<Instruction>) =
            KeyEventReads.analyze(
                X64ControlFlow(body),
                listOf(DwarfRanges.Range(3, 7)),
                listOf(DwarfRanges.Range(7, 9)),
                0x2000,
            )
        for (shift in listOf(0L, 64L)) {
            val fields = analyze(body(shift))
            assertEquals(NativeField(20 + shift, 4), fields["key"])
            assertEquals(NativeField(16 + shift, 4), fields["extended"])
            assertEquals(NativeField(shift, 4), fields["character"])
            assertEquals(NativeField(30 + shift, 1), fields["control"])
        }
        assertFails { analyze(body(240)) }
        assertFails {
            analyze(body().map { if (it.offset == 10L) it.copy(operation = Operation.ADD) else it })
        }
        assertFails {
            analyze(body().map { if (it.offset == 0L) it.copy(source = Register(2, 8)) else it })
        }
        assertFails {
            analyze(
                body().map {
                    if (it.offset == 12L) it.copy(source = Memory(14, null, 1, 4, 4)) else it
                }
            )
        }
        assertFails {
            analyze(
                body().map {
                    if (it.offset == 8L)
                        it.copy(
                            operation = Operation.MOV,
                            destination = Register(7, 8),
                            source = Register(14, 8),
                        )
                    else it
                }
            )
        }
    }
}
