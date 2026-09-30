package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChatConsoleMemberTest {
    private val code = "53 48 89 fb 48 8b 43 18 48 8b 78 28 e8 ef 00 00 00 5b c3"

    @Test
    fun followsTheTypedPlayerToItsConsoleReceiver() {
        assertEquals(40L, ChatConsoleMember.analyze(machineCode(code), 0x10000, 0x10100, 64, 24, 128))
    }

    @Test
    fun rejectsChangedOwnersWidthsAddressesAndBounds() {
        for (invalid in listOf(
            code.replace("48 89 fb", "48 89 f3"), code.replace("43 18", "43 20"),
            code.replace("48 8b 78", "48 8d 78"), code.replace("48 8b 78", "40 8b 78"),
            code.replace("78 28", "78 00"), code.replace("78 28", "78 29"),
            code.replace("e8 ef", "e8 ee"),
        )) assertFails { ChatConsoleMember.analyze(machineCode(invalid), 0x10000, 0x10100, 64, 24, 128) }
        assertFails { ChatConsoleMember.analyze(machineCode(code), 0x10000, 0x10100, 64, 24, 40) }
    }
}
