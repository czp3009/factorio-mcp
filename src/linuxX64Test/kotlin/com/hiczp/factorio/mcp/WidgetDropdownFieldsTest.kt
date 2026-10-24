package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class WidgetDropdownFieldsTest {
    private val wrapper = "53 48 89 fb 48 8d 7e 20 48 8b 46 20 89 d6 ff 50 28 5b c3"

    @Test
    fun verifiesTypedListReceiverAndOriginalIndex() {
        assertEquals(32, WidgetDropdownFields.embedding(machineCode(wrapper), 5, 128, 64))
        assertEquals(
            40,
            WidgetDropdownFields.embedding(machineCode(wrapper.replace("20", "28")), 5, 128, 64),
        )
    }

    @Test
    fun rejectsWrongReceiverTableIndexTargetAndExtent() {
        for (invalid in
            listOf(
                wrapper.replace("7e 20", "7f 20"),
                wrapper.replace("20", "48"),
                wrapper.replace("50 28", "50 30"),
                wrapper.replace("46 20", "46 28"),
                wrapper.replace("89 d6", "89 ce"),
            )) {
            assertFails { WidgetDropdownFields.embedding(machineCode(invalid), 5, 128, 64) }
        }
    }
}
