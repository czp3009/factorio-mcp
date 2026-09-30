package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class EmbeddedPrimaryTableTest {
    private val code = "48 8d 05 f9 00 00 00 48 89 47 20 c3"

    private fun verify(code: String, size: Long = 32) =
        EmbeddedPrimaryTable.analyze(machineCode(code), 0x1000, 0x1100, 96, size)

    @Test
    fun resolvesBoundedOriginalReceiverMember() {
        assertEquals(32, verify(code))
        assertEquals(40, verify(code.replace("47 20", "47 28")))
        assertEquals(32, verify("53 48 89 fb 48 8d 05 f5 00 00 00 48 89 43 20 5b c3"))
    }

    @Test
    fun rejectsWrongTableReceiverWidthBoundsAndInterruptedStore() {
        for (invalid in listOf(
            code.replace("05 f9", "05 f8"), code.replace("47 20", "46 20"),
            code.replace("48 89 47", "90 89 47"), code.replace("47 20", "47 48"),
            code.replace("47 20", "47 21"), code.replace("48 89 47", "48 89 4f"),
            code.replace("48 89 47", "90 48 89 47"),
        )) assertFails { verify(invalid) }
        assertFails { verify(code, 72) }
        assertFails { verify("31 c0 c3") }
        assertFails {
            verify(
                "48 8d 05 f9 00 00 00 48 89 47 20 " +
                        "48 8d 05 ee 00 00 00 48 89 47 40 c3"
            )
        }
    }
}
