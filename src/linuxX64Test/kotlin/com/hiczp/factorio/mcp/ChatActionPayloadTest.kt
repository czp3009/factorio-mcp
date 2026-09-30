package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChatActionPayloadTest {
    private val string = NativeStringLayout(0, 8, 16, 32, ElfImage.Symbol("fixture_destroy", 0x2000, 1, 2, 1))
    private val valid = "49 89 d6 48 89 fb 4c 8d 63 40 4c 89 63 30 4d 8b 3e 4d 8b 76 08 c3"

    private fun analyze(code: String, size: Long = 128, type: Long = 12): Long = ChatActionPayload.analyze(
        X64ControlFlow(X64Instructions(machineCode(code)).all()), size, type, string
    )

    @Test
    fun derivesEmbeddedStorageAndOriginalSourceReads() {
        assertEquals(48, analyze(valid))
        assertEquals(48, analyze("55 48 89 e5 " + valid.dropLast(2) + "5d c3"))
    }

    @Test
    fun rejectsDifferentSourceFieldsOwnershipAndStorageBounds() {
        for (invalid in listOf(
            valid.replace("49 89 d6", "49 89 f6"),
            valid.replace("48 89 fb", "48 89 f3"),
            valid.replace("76 08", "76 10"),
            valid.replace("63 40", "63 48"),
            valid.replace("4d 8b 3e", "45 8b 3e"),
            valid.replace("4d 8b 3e", "49 89 06 4d 8b 3e"),
            valid.replace("4d 8b 3e", "49 83 7e 10 00 4d 8b 3e"),
            valid.replace("4c 8d 63 40", "74 08 4c 8d 63 40"),
        )) assertFails { analyze(invalid) }
        assertFails { analyze(valid, size = 79) }
        assertFails { analyze(valid, type = 48) }
    }
}
