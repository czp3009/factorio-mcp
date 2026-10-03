package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class InlineScalarMemberTest {
    @Test
    fun namedRangeSelectsOnlyOneOriginalReceiverLoad() {
        val flow = X64ControlFlow(X64Instructions(machineCode("48 8b 47 28 48 8b 57 30 c3")).all())
        val selected = listOf(DwarfRanges.Range(0, 4))
        assertEquals(40L, InlineScalarMember.analyze(flow, selected, 64, 8))
        assertFails { InlineScalarMember.analyze(flow, selected, 47, 8) }
        assertFails { InlineScalarMember.analyze(flow, selected, 64, 4) }
        assertFails { InlineScalarMember.analyze(flow, listOf(DwarfRanges.Range(0, 8)), 64, 8) }
        assertFails { InlineScalarMember.analyze(flow, listOf(DwarfRanges.Range(1, 4)), 64, 8) }
        val foreign = X64ControlFlow(X64Instructions(machineCode("48 8b 46 28 c3")).all())
        assertFails { InlineScalarMember.analyze(foreign, selected, 64, 8) }
    }
}
