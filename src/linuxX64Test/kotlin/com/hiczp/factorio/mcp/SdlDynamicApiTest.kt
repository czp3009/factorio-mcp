package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.*

class SdlDynamicApiTest {
    private fun body(registerTarget: Boolean = false): List<Instruction> = buildList {
        fun emit(op: Operation, dst: Operand? = null, src: Operand? = null) {
            add(Instruction(size.toLong(), 1, op, dst, src))
        }
        emit(Operation.PUSH, Register(3, 8))
        emit(Operation.MOV, Register(3, 8), Register(7, 8))
        emit(Operation.NOP)
        emit(Operation.CALL, Immediate(0x1000))
        emit(Operation.MOV, Register(7, 8), Register(3, 8))
        emit(Operation.POP, Register(3, 8))
        val slot = Memory(null, null, 1, 0x2000 - size - 1L, 8, true)
        if (registerTarget) {
            emit(Operation.MOV, Register(0, 8), slot)
            emit(Operation.NOP)
            emit(Operation.JMP, Register(0, 8))
        } else emit(Operation.JMP, slot)
    }

    private fun inspect(body: List<Instruction>) =
        SdlDynamicApi.analyze(body, 0x1000, 0x2000, listOf(7))

    @Test
    fun resolvesForwardingThroughMemoryAndRegisterWithoutAdjacentSetup() {
        assertEquals(0x3000, inspect(body()))
        assertEquals(0x3000, inspect(body(true)))
        assertEquals(0x3000, SdlDynamicApi.analyze(body(), 0x1000, 0x2000, emptyList()))
    }

    @Test
    fun rejectsChangedArgumentsUnrestoredFrameAndOtherCalls() {
        val body = body()
        for ((site, changed) in
            listOf(
                1 to body[1].copy(source = Register(6, 8)),
                3 to body[3].copy(destination = Immediate(0x2000)),
                4 to body[4].copy(destination = Register(6, 8)),
                5 to body[5].copy(destination = Register(12, 8)),
                5 to body[5].copy(operation = Operation.NOP, destination = null),
                6 to body[6].copy(operation = Operation.CALL),
            )) assertFails {
            inspect(
                body.mapIndexed { index, instruction ->
                    if (index == site) changed else instruction
                }
            )
        }
    }
}
