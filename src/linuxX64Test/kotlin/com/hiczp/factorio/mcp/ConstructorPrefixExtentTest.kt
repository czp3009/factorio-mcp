package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ConstructorPrefixExtentTest {
    private val code = "55 48 89 e5 53 48 89 fb 89 0f 66 89 47 04 88 57 06 " +
            "0f 57 c0 0f 11 47 07 c6 47 17 00 85 c0 74 05 e8 00 10 00 00 5b 5d c3"

    @Test
    fun derivesContiguousPrefixIncludingScalarAndVectorWrites() {
        assertEquals(24L, ConstructorPrefixExtent.analyze(machineCode(code)))
        assertEquals(24L, ConstructorPrefixExtent.analyze(machineCode(code.replace("0f 11 47 07", "90 0f 11 43 07 90"))))
        assertEquals(6L, ConstructorPrefixExtent.analyze(machineCode(code.replace("88 57 06", "88 57 28"))))
    }

    @Test
    fun stopsAtDispatchAndRejectsEscapeOrUnprovenReceiver() {
        assertFails { ConstructorPrefixExtent.analyze(machineCode("e8 00 10 00 00 " + code)) }
        assertFails { ConstructorPrefixExtent.analyze(machineCode(code.replace("89 0f", "89 0e"))) }
        assertFails { ConstructorPrefixExtent.analyze(machineCode(code.replace("89 0f", "48 89 3e 89 0f"))) }
        assertEquals(4L, ConstructorPrefixExtent.analyze(machineCode(code.replace("66 89 47 04", "e8 00 10 00 00 66 89 47 04"))))
    }
}
