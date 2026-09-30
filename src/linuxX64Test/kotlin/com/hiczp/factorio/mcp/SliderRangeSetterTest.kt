package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SliderRangeSetterTest {
    private val code = "66 0f 2e 47 28 77 06 f2 0f 11 47 20 c3 c3"

    @Test
    fun derivesPairedRangeMembersWithoutCallingSetter() {
        assertEquals(SliderRangeSetter(32, 40), SliderRangeSetter.analyze(machineCode(code), 64, 7))
        assertEquals(
            SliderRangeSetter(40, 32), SliderRangeSetter.analyze(
                machineCode("66 0f 2e 47 20 72 06 f2 0f 11 47 28 c3 c3"), 64, 2
            )
        )
    }

    @Test
    fun handlesCompilerReversedFloatingComparisonWithoutChangingInput() {
        val reversed = "f2 0f 10 4f 20 66 0f 2e c8 77 06 f2 0f 11 47 28 c3 c3"
        assertEquals(SliderRangeSetter(40, 32), SliderRangeSetter.analyze(machineCode(reversed), 64, 2))
        assertFails { SliderRangeSetter.analyze(machineCode(reversed.replace("10 4f", "10 47")), 64, 2) }
        assertFails { SliderRangeSetter.analyze(machineCode(reversed.replace("77 06", "72 06")), 64, 2) }
    }

    @Test
    fun rejectsChangedReceiverGuardArgumentAndBounds() {
        for (changed in listOf(
            code.replace("2e 47", "2e 46"), code.replace("77 06", "76 06"),
            code.replace("77 06", "77 00"), code.replace("11 47", "11 4f"),
            code.replace("11 47 20", "11 47 28")
        )) {
            assertFails { SliderRangeSetter.analyze(machineCode(changed), 64, 7) }
        }
        assertFails { SliderRangeSetter.analyze(machineCode(code), 47, 7) }
    }
}
