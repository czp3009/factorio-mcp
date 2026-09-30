package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class X64ControlFlowTest {
    @Test
    fun selectedProvenanceKeepsEveryIncomingPathAndLaterBackedge() {
        // TEST; JE alternate; target NOP; JMP back to TEST; alternate RET.
        val flow = X64ControlFlow(X64Instructions(machineCode("85 ff 74 03 90 eb f9 c3")).all())
        assertEquals(setOf(0L, 2L, 4L, 5L, 7L), flow.reachable)
        val selected = flow.reaching(4)
        assertEquals(setOf(0L, 2L, 4L, 5L), selected.reachable)
        assertEquals(setOf(5L), selected.predecessors[0])
        assertEquals(listOf(0L), selected.successors[5])
        assertFails { flow.reaching(3) }
    }
}
