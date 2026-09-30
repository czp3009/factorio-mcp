package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LocalisedRawCallTest {
    private val code = "48 8d 47 10 48 89 07 48 c7 47 08 00 00 00 00 c6 47 10 00 0f b6 46 20 48 83 f8 04"
    private val string = NativeStringLayout(0, 8, 16, 32, ElfImage.Symbol("fixture_destroy", 0x2000, 1, 2, 1))
    private fun verify(
        code: String = this.code,
        size: Long = 96,
        ranges: List<DwarfRanges.Range> = listOf(DwarfRanges.Range(0, 19))
    ) =
        LocalisedRawCall.analyze(X64ControlFlow(X64Instructions(machineCode(code)).all()), 4096, ranges, string, size)

    @Test
    fun identifiesHiddenOutputAndSeparateOriginalReceiver() {
        assertEquals(LocalisedRawCall(4096, 7, 6, 32), verify())
        assertEquals(LocalisedRawCall(4096, 7, 6, 48), verify(code.replace("46 20", "46 30")))
        assertEquals(
            LocalisedRawCall(4096, 7, 6, 32), verify(
                "53 48 83 ec 10 $code",
                ranges = listOf(DwarfRanges.Range(0, 24))
            )
        )
    }

    @Test
    fun rejectsIncorrectStorageReceiverModeAndConstructionRange() {
        for (invalid in listOf(
            code.replace("47 08", "47 18"), code.replace("c6 47 10 00", "c6 47 10 01"),
            code.replace("46 20", "47 20"), code.replace("0f b6", "0f b7"), code.replace("83 f8", "83 f9")
        ))
            assertFails { verify(invalid) }
        assertFails { verify(size = 32) }
        assertFails { verify(ranges = listOf(DwarfRanges.Range(7, 19))) }
    }
}
