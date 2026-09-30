package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class DoubleMemberAccessorTest {
    @Test
    fun readsAndWritesTheSameBoundedDoubleMember() {
        assertEquals(32, DoubleMemberAccessor.analyze(machineCode("f2 0f 10 47 20 c3"), 64))
        assertEquals(40, DoubleMemberAccessor.analyze(machineCode("55 48 89 e5 f2 0f 11 47 28 5d c3"), 64, true))
    }

    @Test
    fun rejectsOtherReceiversWidthsRegistersAndArithmetic() {
        for (code in listOf(
            "f2 0f 10 46 20 c3", "f3 0f 10 47 20 c3", "f2 0f 10 4f 20 c3",
            "f2 0f 10 47 20 f2 0f 58 c0 c3", "55 f2 0f 10 47 20 c3"
        )) {
            assertFails { DoubleMemberAccessor.analyze(machineCode(code), 64) }
        }
        assertFails { DoubleMemberAccessor.analyze(machineCode("f2 0f 10 47 20 c3"), 39) }
        assertFails { DoubleMemberAccessor.analyze(machineCode("f2 0f 11 47 20 c3"), 64) }
    }
}
