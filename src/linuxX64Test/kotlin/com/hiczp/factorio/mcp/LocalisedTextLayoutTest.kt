package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LocalisedTextLayoutTest {
    private val string = NativeStringLayout(0, 8, 16, 32, ElfImage.Symbol("fixture", 0x10000, 1, 2, 1))
    private val code = "48 c7 47 38 00 00 00 00 48 8b 47 30 c6 00 00 c3"

    @Test
    fun derivesCacheFromLengthAndBufferClearing() {
        assertEquals(LocalisedTextLayout(96, 48), LocalisedTextLayout.analyze(machineCode(code), 0x10000, 96, string))
    }

    @Test
    fun rejectsUnrelatedBuffersWidthsOwnersAndExtents() {
        for (invalid in listOf(
            code.replace("47 38", "47 40"), code.replace("47 30", "47 28"),
            code.replace("48 c7", "40 c7"), code.replace("47 30", "46 30"),
            code.replace("c6 00 00", "c6 00 01"),
        )) assertFails { LocalisedTextLayout.analyze(machineCode(invalid), 0x10000, 96, string) }
        assertFails { LocalisedTextLayout.analyze(machineCode(code), 0x10000, 72, string) }
    }
}
