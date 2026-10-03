package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SysVDeletingTailSizeTest {
    private fun analyze(
        size: String = "be 40 00 00 00",
        receiver: String = "48 89 df",
        restore: String = "48 83 c4 08 5b 5d",
        tail: String = "e9 00 20 00 00",
        padding: Int = 300,
    ): Long {
        val code =
            "55 48 89 e5 53 50 48 89 fb e8 00 10 00 00 " +
                "90 ".repeat(padding) +
                "$size 90 $receiver $restore $tail"
        val flow = X64ControlFlow(X64Instructions(machineCode(code.trim())).all(8192))
        val end = flow.instructions.last()
        return SysVDeletingTailSize.analyze(flow, end.offset + end.size + 0x2000)
    }

    @Test
    fun followsOriginalReceiverAcrossInlinedCleanupAndCompleteArgumentSetup() {
        assertEquals(64L, analyze())
        assertEquals(96L, analyze(size = "48 c7 c6 60 00 00 00"))
        assertEquals(64L, analyze(padding = 0))
    }

    @Test
    fun rejectsWrongReceiversSizesExitsAndUnrestoredFrames() {
        assertFailsWith<IllegalArgumentException> { analyze(receiver = "48 89 f7") }
        assertFailsWith<IllegalArgumentException> { analyze(receiver = "48 8d 7b 08") }
        assertFailsWith<IllegalArgumentException> { analyze(size = "be 00 00 00 00") }
        assertFailsWith<IllegalArgumentException> { analyze(size = "be ff ff ff 7f") }
        assertFailsWith<IllegalStateException> { analyze(size = "40 b6 40") }
        assertFailsWith<IllegalStateException> {
            analyze(size = "85 c9 74 07 be 40 00 00 00 eb 05 be 60 00 00 00")
        }
        assertFailsWith<IllegalArgumentException> { analyze(restore = "90") }
        assertFailsWith<IllegalArgumentException> { analyze(tail = "c3") }
        assertFailsWith<IllegalArgumentException> { analyze(tail = "e9 01 20 00 00") }
    }
}
