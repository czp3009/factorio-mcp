package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class WidgetRenderFlagTest {
    private val code = "41 f6 44 24 31 20 74 0a 49 8b 04 24 4c 89 e7 ff 50 28 c3"

    @Test
    fun derivesFlagFromNamedRangeAndSameTypedReceiver() {
        assertEquals(
            NativeAccessor(49, 1, 32UL, 5),
            WidgetRenderFlag.analyze(machineCode(code), 0, 6, 64, 5)
        )
        val other = "41 f7 45 50 00 02 00 00 74 0a 49 8b 45 00 4c 89 ef ff 50 30 c3"
        assertEquals(
            NativeAccessor(81, 1, 2UL, 1),
            WidgetRenderFlag.analyze(machineCode(other), 0, 8, 96, 6)
        )
        val copied = "41 f6 44 24 31 20 74 0d 49 8b 04 24 4d 89 e6 4c 89 f7 ff 50 28 c3"
        assertEquals(
            NativeAccessor(49, 1, 32UL, 5),
            WidgetRenderFlag.analyze(machineCode(copied), 0, 6, 64, 5)
        )
    }

    @Test
    fun rejectsUnknownMasksReceiversSlotsBranchesAndBounds() {
        for (changed in listOf(
            code.replace("31 20", "31 60"),
            code.replace("31 20", "31 00"),
            code.replace("74 0a", "75 0a"),
            code.replace("74 0a", "74 05"),
            code.replace("49 8b 04 24", "49 8b 04 25"),
            code.replace("4c 89 e7", "4c 89 ef"),
            code.replace("ff 50 28", "ff 50 30"),
            code.replace("4c 89 e7", "4c 89 e6"),
            code.replace("4c 89 e7", "4c 31 e7"),
            code.replace("49 8b 04 24", "49 89 04 24"),
        )) assertFails(changed) { WidgetRenderFlag.analyze(machineCode(changed), 0, 6, 64, 5) }
        assertFails { WidgetRenderFlag.analyze(machineCode(code), 0, 5, 64, 5) }
        assertFails { WidgetRenderFlag.analyze(machineCode(code), 1, 6, 64, 5) }
        assertFails { WidgetRenderFlag.analyze(machineCode(code), 0, 6, 49, 5) }
    }
}
