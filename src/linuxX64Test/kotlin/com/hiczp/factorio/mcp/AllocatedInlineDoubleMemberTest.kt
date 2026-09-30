package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class AllocatedInlineDoubleMemberTest {
    private val prefix = "53 bf 60 00 00 00 e8 f5 00 00 00 48 89 c3 48 8d 05 eb 01 00 00 48 89 03 "
    private val suffix = "48 89 d8 5b c3"
    private fun verify(code: String, end: Long = 29) = AllocatedInlineDoubleMember.analyze(
        machineCode(code), 0x1000, 0x1100, 96, 0x1200, listOf(DwarfRanges.Range(24, end))
    )

    @Test
    fun bindsNamedStoreAndPrimaryTableToSameSizedAllocation() {
        assertEquals(32, verify(prefix + "f2 0f 11 43 20 " + suffix))
        assertEquals(40, verify(prefix + "f2 0f 11 43 28 " + suffix))
        assertEquals(32, verify(prefix + "f2 0f 5f c1 f2 0f 5d c2 f2 0f 11 43 20 " + suffix, 37))
        val minmax = X64Instructions(machineCode("f2 0f 5f c1 f2 0f 5d c2")).all()
        assertEquals(
            listOf(X64Instructions.Operation.DOUBLE_MAXIMUM, X64Instructions.Operation.DOUBLE_MINIMUM),
            minmax.map { it.operation })
        assertEquals(X64Instructions.Register(18, 8), minmax[1].source)
    }

    @Test
    fun rejectsWrongIdentityAllocationReceiverWidthAndRange() {
        val code = prefix + "f2 0f 11 43 20 " + suffix
        for (invalid in listOf(
            code.replace("bf 60", "bf 58"), code.replace("e8 f5", "e8 f4"),
            code.replace("05 eb", "05 ea"), code.replace("48 89 03", "48 89 07"),
            code.replace("11 43 20", "11 47 20"), code.replace("11 43 20", "11 43 59"),
            code.replace("11 43 20", "11 43 00"), code.replace("f2 0f 11", "f3 0f 11"),
            code.replace("48 89 c3", "48 89 f3"),
        )) assertFails { verify(invalid) }
        assertFails { verify(code, 28) }
        assertFails { verify(prefix + "f2 0f 11 43 20 f2 0f 11 43 28 " + suffix, 34) }
        for (code in listOf("f2 48 0f 5d c1", "f2 0f 5f", "66 f2 0f 5d c1")) {
            assertFails { X64Instructions(machineCode(code)).all() }
        }
    }
}
