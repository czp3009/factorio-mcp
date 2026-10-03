package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertFailsWith

class GuiDirectionArgumentTest {
    private fun flow(code: String) = X64ControlFlow(X64Instructions(machineCode(code)).all())

    @Test
    fun forwardsTheSameLowByteFromBoundedOriginalStorage() {
        for (load in
            listOf("41 0f b6 76 10", "41 0f b7 76 10", "41 8b 76 10", "41 8b 46 10 90 89 c6")) {
            val body = flow("41 56 49 89 fe e8 00 10 00 00 $load 90 e8 00 20 00 00 41 5e c3")
            val call = body.instructions.last { it.operation == X64Instructions.Operation.CALL }
            GuiDirectionArgument.forwards(body, call.offset, 32, 16)
        }
        for (load in
            listOf("41 8b 76 11", "41 8b 76 1f", "41 8b 76 10 83 c6 01", "41 8b 76 10 31 f6")) {
            val body = flow("41 56 49 89 fe $load e8 00 20 00 00 41 5e c3")
            val call = body.instructions.last { it.operation == X64Instructions.Operation.CALL }
            assertFailsWith<IllegalArgumentException> {
                GuiDirectionArgument.forwards(body, call.offset, 32, 16)
            }
        }
        val clobbered = flow("41 56 49 89 fe 41 8b 76 10 e8 00 30 00 00 e8 00 20 00 00 41 5e c3")
        val call = clobbered.instructions.last { it.operation == X64Instructions.Operation.CALL }
        assertFailsWith<IllegalStateException> {
            GuiDirectionArgument.forwards(clobbered, call.offset, 32, 16)
        }
    }

    @Test
    fun consumesOnlyTheUnchangedOriginalInputByte() {
        fun verify(code: String) {
            val body = flow(code)
            val test = body.instructions.last { it.operation == X64Instructions.Operation.TEST }
            GuiDirectionArgument.consumes(body, test.offset)
        }
        verify("41 54 41 89 f4 90 e8 00 10 00 00 45 84 e4 41 5c c3")
        verify("41 54 41 89 f4 85 ff 74 01 90 45 84 e4 41 5c c3")
        for (invalid in
            listOf(
                "41 54 41 89 d4 45 84 e4 41 5c c3",
                "41 54 41 89 f4 41 83 c4 01 45 84 e4 41 5c c3",
                "53 e8 00 10 00 00 40 84 f6 5b c3",
            )) {
            assertFailsWith<IllegalStateException> { verify(invalid) }
        }
    }
}
