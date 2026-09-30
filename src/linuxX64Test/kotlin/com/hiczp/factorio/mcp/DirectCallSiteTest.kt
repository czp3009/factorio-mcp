package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class DirectCallSiteTest {
    @Test
    fun usesDecodedInstructionBoundariesAndTheExactDirectTarget() {
        // The immediate in MOV contains a decoy CALL byte. Only the later direct instruction counts.
        val bytes = machineCode("48 b8 e8 00 00 00 00 00 00 00 ff d0 e8 ef 00 00 00 90")
        assertEquals(17L, DirectCallSite.firstReturn(bytes, 256))
        assertFails { DirectCallSite.firstReturn(bytes, 257) }
        assertFails { DirectCallSite.firstReturn(machineCode("ff d0 c3"), 256) }
    }

    @Test
    fun rejectsTruncatedUnknownAndOverlongPrefixes() {
        assertFails { DirectCallSite.firstReturn(machineCode("e8 01 02"), 256) }
        assertFails { DirectCallSite.firstReturn(machineCode("0f 0b e8 f9 00 00 00"), 256) }
        assertFails { DirectCallSite.firstReturn(machineCode("c3 e8 fa 00 00 00"), 256) }
        assertFails { DirectCallSite.firstReturn(BinaryView(ByteArray(4097) { 0x90.toByte() }), 256) }
        assertFails { DirectCallSite.firstReturn(BinaryView(ByteArray(513) { 0x90.toByte() }), 256) }
    }
}
