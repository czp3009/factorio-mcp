package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class VectorCountAccessorTest {
    private val code = "55 48 89 e5 48 8b 47 28 48 2b 47 20 48 c1 e8 04 5d c3"

    @Test
    fun derivesIndependentPointerMembersAndElementExtent() {
        assertEquals(VectorCountAccessor(32, 40, 16), VectorCountAccessor.analyze(machineCode(code), 64))
        assertEquals(
            VectorCountAccessor(40, 32, 32), VectorCountAccessor.analyze(
                machineCode("48 8b 47 20 48 2b 47 28 48 c1 f8 05 c3"), 64
            )
        )
    }

    @Test
    fun rejectsChangedReceiverWidthReturnArithmeticAndBounds() {
        for (invalid in listOf(
            code.replace("8b 47", "8b 46"), code.replace("2b 47", "2b 46"),
            code.replace("47 28", "47 3c"), code.replace("47 28", "47 24"),
            code.replace("48 2b", "48 03"), code.replace("e8 04", "e0 04"),
            code.replace("e8 04", "e8 02"), code.replace("e8 04", "e8 0d"),
            code.replace("48 8b", "90 8b"), code.replace("c1 e8", "c1 e9")
        )) {
            assertFails { VectorCountAccessor.analyze(machineCode(invalid), 64) }
        }
    }
}
