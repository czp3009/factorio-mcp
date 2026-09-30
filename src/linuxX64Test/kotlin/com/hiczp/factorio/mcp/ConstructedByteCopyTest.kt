package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ConstructedByteCopyTest {
    private val code = "53 41 56 49 89 fe bf 60 00 00 00 e8 f0 00 00 00 48 89 c3 " +
            "48 89 df e8 e5 01 00 00 41 0f b6 46 10 88 43 20 41 5e 5b c3"

    private fun verify(code: String) = ConstructedByteCopy.analyze(
        machineCode(code), 0x1000, 0x1100, 0x1200, 96, 64, 16
    )

    @Test
    fun copiesIdentifiedOriginalByteIntoConstructedObject() {
        assertEquals(32, verify(code))
        assertEquals(40, verify(code.replace("88 43 20", "88 43 28")))
    }

    @Test
    fun rejectsUnrelatedSourceAndInterruptedOrUnboundedCopy() {
        for (invalid in listOf(
            code.replace("49 89 fe", "49 89 f6"), code.replace("b6 46 10", "b6 46 11"),
            code.replace("0f b6", "0f b7"), code.replace("88 43 20", "88 4b 20"),
            code.replace("88 43 20", "90 88 43 20"), code.replace("88 43 20", "88 47 20"),
            code.replace("88 43 20", "88 43 60"), code.replace("e8 e5", "e8 e6"),
        )) assertFails { verify(invalid) }
    }
}
