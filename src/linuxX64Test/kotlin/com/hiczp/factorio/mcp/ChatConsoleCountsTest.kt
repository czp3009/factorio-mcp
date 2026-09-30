package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertFails

class ChatConsoleCountsTest {
    private val code = "48 ff 4f 18 48 ff 4f 30 c3"
    private val ranges = listOf(DwarfRanges.Range(0, 4), DwarfRanges.Range(4, 8))
    private fun verify(code: String, ranges: List<DwarfRanges.Range> = this.ranges) =
        ChatConsoleCounts.analyze(X64ControlFlow(X64Instructions(machineCode(code)).all()), ranges, setOf(24, 48))

    @Test
    fun matchesTwoOriginalConsoleFieldsToNamedDecrements() = verify(code)

    @Test
    fun rejectsChangedCountersOwnersWidthsAndInlineRanges() {
        for (invalid in listOf(
            code.replace("4f 18", "4e 18"), code.replace("4f 30", "4f 18"),
            code.replace("4f 18", "4f 20"), code.replace("48 ff", "40 ff")
        )) assertFails { verify(invalid) }
        assertFails { verify(code, listOf(DwarfRanges.Range(1, 8))) }
        assertFails { verify(code, ranges + DwarfRanges.Range(8, 9)) }
        assertFails { verify(code, emptyList()) }
    }
}
