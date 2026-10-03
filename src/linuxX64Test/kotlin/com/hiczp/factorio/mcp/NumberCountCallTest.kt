package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class NumberCountCallTest {
    @Test
    fun findsCountAfterUnrelatedCallsAndPreservedReceiverMoves() {
        val bytes = machineCode("53 48 89 fb e8 00 10 00 00 48 89 df 48 8b 0b ff 51 38 " +
                "90 66 0f 57 c9 f2 0f 11 04 24 5b c3")
        assertEquals(15L, NumberCountCall.analyze(bytes, 0x1000, 7))
        val moved = machineCode("48 83 ec 08 48 8b 07 ff 50 38 90 f2 0f 10 c8 f2 0f 11 0c 24 48 83 c4 08 c3")
        assertEquals(7L, NumberCountCall.analyze(moved, 0x1000, 7))
        val vectorCopy = machineCode("53 48 8b 07 ff 50 38 66 0f 28 c8 f2 0f 11 0c 24 5b c3")
        assertEquals(4L, NumberCountCall.analyze(vectorCopy, 0x1000, 7))
    }

    @Test
    fun followsLoadedVirtualTargetsWithoutRequiringAnAdjacentCall() {
        val code = "53 48 8b 07 4c 8b 58 38 90 41 ff d3 f2 0f 11 04 24 5b c3"
        assertEquals(9L, NumberCountCall.analyze(machineCode(code), 0x1000, 7))
        val copied = code.replace("90 41 ff d3", "4c 89 d8 90 ff d0")
        assertEquals(12L, NumberCountCall.analyze(machineCode(copied), 0x1000, 7))
        for (changed in listOf(
            code.replace("58 38", "58 30"),
            code.replace("4c 8b 58", "44 8b 58"),
            code.replace("48 8b 07", "48 8b 06"),
            code.replace("90", "45 31 db"),
            code.replace("90", "e8 00 10 00 00"),
            code.replace("90", "48 89 f7"),
            code.replace("90", "4d 8b 1b"),
        )) assertFails { NumberCountCall.analyze(machineCode(changed), 0x1000, 7) }
    }

    @Test
    fun excludesUnrelatedCallbackReturnsBeforeAnalyzingTheirReceiverPaths() {
        val code = "53 48 89 fb 48 8b 07 ff 50 38 f2 0f 11 04 24 " +
                "48 89 df f2 48 0f 2a c0 48 8b 07 48 8b 40 10 ff d0 31 c0 5b c3"
        assertEquals(7L, NumberCountCall.analyze(machineCode(code), 0x1000, 7))
    }

    @Test
    fun rejectsWrongReceiversSlotsWidthsOrOverwrittenReturns() {
        for (code in listOf(
            "48 8b 06 ff 50 38 f2 0f 11 04 24 c3",
            "48 8b 07 48 89 f7 ff 50 38 f2 0f 11 04 24 c3",
            "48 8b 07 ff 50 30 f2 0f 11 04 24 c3",
            "48 8b 07 ff 50 38 f3 0f 11 04 24 c3",
            "48 8b 07 ff 50 38 66 0f 57 c0 f2 0f 11 04 24 c3",
            "48 8b 07 ff 50 38 e8 00 10 00 00 f2 0f 11 04 24 c3",
        )) assertFails { NumberCountCall.analyze(machineCode(code), 0x1000, 7) }
    }

    @Test
    fun provesBooleanLeafReturnWithoutAssumingAFramePointer() {
        for (code in listOf("b0 01 c3", "31 c0 c3", "55 48 89 e5 b0 01 5d c3",
                "b9 01 00 00 00 89 c8 90 c3")) BooleanLeafReturn.analyze(machineCode(code))
        for (code in listOf("b0 02 c3", "89 f0 c3", "88 07 c3", "e8 00 10 00 00 c3", "90 c3"))
            assertFails { BooleanLeafReturn.analyze(machineCode(code)) }
    }
}
