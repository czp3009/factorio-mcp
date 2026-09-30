package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class InlineBooleanMemberTest {
    private fun analyze(code: String, end: Long, size: Long = 64): Long = InlineBooleanMember.analyze(
        machineCode(code), listOf(DwarfRanges.Range(0, end)), size
    )

    @Test
    fun readsOwnByteWithoutEvaluatingLaterIndirectStylePredicate() {
        val code = "80 7f 20 01 75 09 48 8b 07 80 78 08 01 75 00 c3"
        assertEquals(32, analyze(code, 15))
        assertEquals(32, analyze("80 7f 20 01 b8 00 00 00 00 75 01 90 c3", 11))
        assertFails { analyze(code, 15, 32) }
    }

    @Test
    fun rejectsDifferentReceiverWidthConstantBranchAndAmbiguity() {
        val code = "80 7f 20 01 75 01 90 c3"
        for (invalid in listOf(
            code.replace("7f", "7e"), code.replace("80", "83"),
            code.replace("20 01", "20 02"), code.replace("75", "74"), code.replace("75 01", "75 00")
        )) {
            assertFails { analyze(invalid, 6) }
        }
        assertFails { analyze(code, 5) }
        assertFails { analyze("80 7f 20 01 75 07 80 7f 21 01 75 01 90 c3", 12) }
        assertFails { analyze("80 7f 20 01 b8 01 00 00 00 75 01 90 c3", 11) }
    }
}
