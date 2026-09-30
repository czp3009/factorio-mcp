package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class WidgetDropdownFieldsTest {
    private val wrapper = "55 48 89 e5 53 50 48 89 fb 48 83 c6 20 e8 ee 00 00 00 " +
            "48 89 d8 48 83 c4 08 5b 5d c3"

    @Test
    fun verifiesHiddenStringOutputAndEmbeddedListReceiver() {
        assertEquals(32, WidgetDropdownFields.embedding(machineCode(wrapper), 0x100, 128, 64))
        assertEquals(40, WidgetDropdownFields.embedding(machineCode(wrapper.replace("c6 20", "c6 28")), 0x100, 128, 64))
    }

    @Test
    fun rejectsWrongReceiverOutputTargetAndExtent() {
        for (invalid in listOf(
            wrapper.replace("c6 20", "c7 20"), wrapper.replace("c6 20", "c6 48"),
            wrapper.replace("e8 ee", "e8 ed"), wrapper.replace("89 d8", "89 f0"),
            wrapper.replace("89 fb", "89 d3")
        )) {
            assertFails { WidgetDropdownFields.embedding(machineCode(invalid), 0x100, 128, 64) }
        }
    }
}
