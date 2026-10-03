package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PdbSymbolCandidatesTest {
    @Test
    fun aliasesRetainOneEntryButDifferentEntriesRemainAmbiguousInEitherOrder() {
        for (order in listOf(listOf(0x1000uL, 0x2000uL), listOf(0x2000uL, 0x1000uL))) {
            val matches = PdbSymbolCandidates(3)
            repeat(3) { matches.add(0, 0x3000uL) }
            order.forEach { matches.add(1, it) }
            assertEquals(1, matches.count(0))
            assertEquals(2, matches.count(1))
            assertEquals(0, matches.count(2))
            assertEquals(listOf(0x3000uL, 0uL, 0uL), matches.addresses())
            assertFails { matches.add(2, 0uL) }
        }
    }
}
