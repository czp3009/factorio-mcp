package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChatActionHeaderTest {
    private val constructor = "55 48 89 e5 41 57 53 41 89 f7 48 89 fb 66 44 89 7b 14 89 f7 e8 e7 00 00 00"

    @Test
    fun connectsOriginalConstructorTypeToDestructionAndSubmission() {
        val field = ChatActionHeader.construction(machineCode(constructor), 96, 0x100)
        assertEquals(20, field)
        ChatActionHeader.destruction(machineCode("55 48 89 e5 53 50 0f b7 47 14 c3"), 96, field)
        ChatActionHeader.submission(machineCode("48 85 ff 74 04 0f b7 46 14 c3"), 96, field)
        ChatActionHeader.submission(machineCode("0f b7 46 14 ff e0"), 96, field)
    }

    @Test
    fun rejectsChangedScalarReceiverBoundsAndCall() {
        for (invalid in listOf(
            constructor.replace("41 89 f7", "41 89 d7"),
            constructor.replace("48 89 fb", "48 89 d3"),
            constructor.replace("89 f7 e8", "89 d7 e8"),
            constructor.replace("7b 14", "7b 60"),
            constructor.replace("e8 e7", "e8 e6"),
            constructor.replace("89 f7 e8 e7", "66 c7 43 14 00 00 89 f7 e8 e1"),
        )) assertFails { ChatActionHeader.construction(machineCode(invalid), 96, 0x100) }
        assertFails { ChatActionHeader.construction(machineCode(constructor), 21, 0x100) }
    }

    @Test
    fun rejectsDifferentDestructorAndSubmissionObjectsOrFields() {
        for (invalid in listOf("0f b7 46 14 c3", "0f b7 47 16 c3", "0f b6 47 14 c3"))
            assertFails { ChatActionHeader.destruction(machineCode(invalid), 96, 20) }
        for (invalid in listOf(
            "0f b7 47 14 c3", "0f b7 46 16 c3", "0f b6 46 14 c3",
            "48 89 fe 0f b7 46 14 c3", "0f b7 46 14 0f b7 4e 14 c3"
        ))
            assertFails { ChatActionHeader.submission(machineCode(invalid), 96, 20) }
    }
}
