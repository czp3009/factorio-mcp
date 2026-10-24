package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.*

class GlLoaderBindingTest {
    private fun body() =
        listOf(
            Instruction(
                0,
                1,
                Operation.LEA,
                Register(12, 8),
                Memory(null, null, 1, 0x1fff, 8, true),
            ),
            Instruction(1, 1, Operation.NOP),
            Instruction(2, 1, Operation.MOV, Register(7, 8), Register(12, 8)),
            Instruction(3, 1, Operation.CALL, Memory(null, null, 1, 0x2ffc, 8, true)),
            Instruction(4, 1, Operation.MOV, Register(3, 8), Register(0, 8)),
            Instruction(5, 1, Operation.NOP),
            Instruction(6, 1, Operation.MOV, Memory(null, null, 1, 0xff9, 8, true), Register(3, 8)),
            Instruction(7, 1, Operation.RET),
        )

    private fun inspect(body: List<Instruction> = body(), name: String = "glReadPixels") =
        GlLoaderBinding.analyze(X64ControlFlow(body), 0x1000, 0x4000, 0x2000, "glReadPixels") {
            assertEquals(0x3000, it)
            name
        }

    @Test
    fun connectsLookupNameAndCopiedResultWithoutAdjacentSetup() {
        inspect()
        assertFails { inspect(name = "glOther") }
        for ((site, changed) in
            listOf(
                2 to body()[2].copy(source = Register(6, 8)),
                3 to body()[3].copy(destination = Memory(null, null, 1, 0x2ffd, 8, true)),
                4 to body()[4].copy(source = Register(2, 8)),
                6 to body()[6].copy(source = Register(3, 4)),
                6 to body()[6].copy(destination = Memory(null, null, 1, 0xffa, 8, true)),
            )) assertFails {
            inspect(
                body().mapIndexed { index, instruction ->
                    if (index == site) changed else instruction
                }
            )
        }
    }

    @Test
    fun rejectsAlternativeWritesAndAmbiguousCallResults() {
        val body = body().toMutableList()
        body[1] = Instruction(1, 1, Operation.JCC, Immediate(6), condition = 4)
        assertFails { inspect(body) }
        body[1] =
            Instruction(1, 1, Operation.MOV, Memory(null, null, 1, 0xffe, 8, true), Immediate(0))
        assertFails { inspect(body) }
    }
}
