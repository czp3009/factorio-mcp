package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChatActionCallTest {
    @Test
    fun derivesActionTypeAndRequiresOneConstructedLocalThroughSubmissionAndCleanup() {
        // All three external call targets are fixture addresses relative to this synthetic body.
        val code = "48 83 ec 28 48 89 e7 be 34 12 00 00 e8 ef 00 00 00 " +
                "48 89 e6 e8 e7 01 00 00 48 89 e7 e8 df 02 00 00 48 83 c4 28 c3"

        fun analyze(bytes: String) = ChatActionCall.analyze(
            X64ControlFlow(X64Instructions(machineCode(bytes)).all()), 32, 0x100, 0x300, 0x200
        )
        assertEquals(ChatActionCall(0x1234, 32), analyze(code))
        assertEquals(ChatActionCall(0x5678, 32), analyze(code.replace("34 12", "78 56")))
        assertFails { analyze(code.replace("48 89 e6", "48 89 ee")) }
        assertFails { analyze(code.replace("be 34 12 00 00", "be 34 12 01 00")) }
        assertFails { analyze(code.replace("e8 df 02 00 00", "90 90 90 90 90")) }
        assertFails {
            ChatActionCall.analyze(
                X64ControlFlow(X64Instructions(machineCode(code)).all()), 64, 0x100, 0x300, 0x200
            )
        }
    }
}
