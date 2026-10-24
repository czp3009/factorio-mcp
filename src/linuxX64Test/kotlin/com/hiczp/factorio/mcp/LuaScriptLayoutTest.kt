package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LuaScriptLayoutTest {
    @Test
    fun attributesMembersAndPreservesIdentityAcrossAReorderedAcyclicBlock() {
        // An out-of-line block reloads the same field before returning to the call site.
        val code = "53 48 89 fb eb 0a 48 89 df e8 f2 00 00 00 5b c3 " + "48 8b 5b 18 eb f0"
        val flow = X64ControlFlow(X64Instructions(machineCode(code)).all())
        assertEquals(24L, LuaScriptLayout.stateMember(flow, 256, 32))
        assertEquals(
            OriginalPointerOrigins.Load(OriginalPointerOrigins.Argument(7), 24, 16),
            OriginalPointerOrigins(flow).register(9, 7),
        )
        assertEquals(
            OriginalPointerOrigins.MemberSource(7, 24),
            OriginalPointerOrigins(flow, attributeMembers = true).register(9, 7),
        )
    }

    @Test
    fun observesStateAcrossExistingNativeCallsWithPrivateLocals() {
        // RBX holds the loaded state; the unrelated call borrows a private local through RDI.
        val code =
            "53 48 83 ec 10 48 8b 5f 18 48 89 e7 e8 ef 01 00 00 " +
                "48 89 df e8 e7 00 00 00 48 83 c4 10 5b c3"
        fun analyze(text: String, extent: Long = 32): Long =
            LuaScriptLayout.stateMember(
                X64ControlFlow(X64Instructions(machineCode(text)).all()),
                256,
                extent,
            )
        assertEquals(24L, analyze(code))
        assertFails { analyze(code, 31) }
        assertFails { analyze(code.replace("5f 18", "5e 18")) }
        assertFails { analyze(code.replace("48 89 df", "48 89 d7")) }
        assertFails { analyze(code.replace("48 89 df", "89 df 90")) }
        assertFails { analyze(code.replace("ec 10", "ec 08")) }
    }
}
