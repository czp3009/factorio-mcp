package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChatSubmissionGateTest {
    @Test
    fun preservesOriginalPlayerChainAndNotEqualDispatchArm() {
        val code = "48 83 ec 28 48 8b 3f 48 8b 47 18 48 8b 40 20 48 8b 40 28 " +
                "83 78 30 07 74 09 48 8d 34 24 e8 de 00 00 00 48 83 c4 28 c3"

        fun analyze(value: String) = ChatSubmissionGate.analyze(machineCode(value), 0x1000, 0x1100)
        assertEquals(ChatSubmissionGate(24, 32, 40, 48, 7), analyze(code))
        for (changed in listOf(
            code.replace("74 09", "75 09"),
            code.replace("74 09", "74 00"),
            code.replace("48 8b 47 18", "48 8b 46 18"),
            code.replace("83 78 30 07", "83 7f 30 07"),
        )) assertFails { analyze(changed) }
    }
}
