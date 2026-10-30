package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.*

class RenderingSettingTest {
    private fun body(receiver: Int = 3, length: Int = 12) = listOf(
        Instruction(0, 1, Operation.MOV, Register(receiver, 8), Register(7, 8)),
        Instruction(1, 1, Operation.MOV, Register(length, 8), Memory(2, null, 1, 16, 8)),
        Instruction(2, 1, Operation.LEA, Register(0, 8), Memory(receiver, null, 1, 48, 8)),
        Instruction(3, 1, Operation.MOV, Memory(receiver, null, 1, 24, 8), Register(0, 8)),
        Instruction(4, 1, Operation.CALL, Immediate(1000)),
        Instruction(5, 1, Operation.MOV, Memory(receiver, null, 1, 40, 8), Register(length, 8)),
        Instruction(6, 1, Operation.RET),
    )

    private fun inspect(code: List<Instruction>, size: Long = 128) =
        RenderingMetadata.settingName(X64ControlFlow(code), 0, 0, 16, 24, 40, size)

    @Test
    fun followsOriginalNameLengthAcrossRegisterAllocationAndCalls() {
        assertEquals(24L, inspect(body()))
        assertEquals(24L, inspect(body(13, 15)))
        val capacity = body().dropLast(1) + listOf(
            Instruction(6, 1, Operation.MOV, Memory(3, null, 1, 48, 8), Register(12, 8)),
            Instruction(7, 1, Operation.RET),
        )
        assertEquals(24L, inspect(capacity))
    }

    @Test
    fun rejectsUnrelatedArgumentsClobberedValuesAmbiguityAndBounds() {
        val body = body()
        assertFails { inspect(body.map { if (it.offset == 1L) it.copy(source = Memory(6, null, 1, 16, 8)) else it }) }
        assertFails { inspect(body(3, 0)) }
        assertFails { inspect(body.map { if (it.offset == 5L) it.copy(destination = Memory(3, null, 1, 40, 4)) else it }) }
        assertFails { inspect(body.map { if (it.offset == 2L) it.copy(source = Memory(3, null, 1, 56, 8)) else it }) }
        assertFails { inspect(body, size = 56) }
        assertFails { inspect(body.dropLast(1) + listOf(
            Instruction(6, 1, Operation.LEA, Register(0, 8), Memory(3, null, 1, 72, 8)),
            Instruction(7, 1, Operation.MOV, Memory(3, null, 1, 48, 8), Register(0, 8)),
            Instruction(8, 1, Operation.MOV, Memory(3, null, 1, 64, 8), Register(12, 8)),
            Instruction(9, 1, Operation.RET),
        )) }
    }
}
