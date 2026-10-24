package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OriginalPointerOriginsTest {
    @Test
    fun preservesLoadedAliasesAcrossReorderedBlocksButNotAnotherLoopIteration() {
        val prefix = "48 8b 47 18 4c 8d 40 08 eb 01 c3 90 "
        fun analyze(suffix: String) =
            OriginalPointerOrigins(
                X64ControlFlow(X64Instructions(machineCode(prefix + suffix)).all())
            )
        val loaded = OriginalPointerOrigins.Load(OriginalPointerOrigins.Argument(7), 24, 0)
        val reordered = analyze("eb fc")
        assertEquals(loaded, reordered.register(10, 0))
        assertEquals(OriginalPointerOrigins.Adjusted(loaded, 8), reordered.register(10, 8))
        val loop = analyze("75 fd c3")
        assertNull(loop.register(14, 0))
        assertNull(loop.register(14, 8))
    }
}
