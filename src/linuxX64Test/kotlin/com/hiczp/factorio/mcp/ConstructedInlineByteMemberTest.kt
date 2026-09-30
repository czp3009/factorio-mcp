package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ConstructedInlineByteMemberTest {
    private val code = "53 bf 60 00 00 00 e8 f5 00 00 00 48 89 c3 48 89 df " +
            "e8 ea 01 00 00 88 43 20 48 89 d8 5b c3"

    private fun verify(bytes: String, start: Long = 22, end: Long = 25) =
        ConstructedInlineByteMember.analyze(
            machineCode(bytes), 0x1000, 0x1100, 0x1200, 96,
            listOf(DwarfRanges.Range(start, end))
        )

    @Test
    fun associatesNamedByteWithConstructedAllocation() {
        assertEquals(32, verify(code))
        assertEquals(40, verify(code.replace("88 43 20", "88 43 28")))
    }

    @Test
    fun rejectsWrongAllocationConstructorReceiverWidthAndRange() {
        for (invalid in listOf(
            code.replace("bf 60", "bf 58"), code.replace("e8 f5", "e8 f4"),
            code.replace("e8 ea", "e8 eb"), code.replace("48 89 df", "48 89 f7"),
            code.replace("48 89 c3", "48 89 f3"), code.replace("88 43 20", "88 47 20"),
            code.replace("88 43 20", "89 43 20"), code.replace("88 43 20", "88 43 60"),
            code.replace("88 43 20", "88 43 00"),
        )) assertFails { verify(invalid) }
        assertFails { verify(code, 23) }
        assertFails { verify(code, end = 24) }
        assertFails { verify(code, end = 28) }
    }

    @Test
    fun rejectsPathBypassingConstruction() {
        val bypass = "53 bf 60 00 00 00 e8 f5 00 00 00 48 89 c3 85 c9 74 08 " +
                "48 89 df e8 e6 01 00 00 88 43 20 48 89 d8 5b c3"
        assertFails { verify(bypass, 26, 29) }
    }
}
