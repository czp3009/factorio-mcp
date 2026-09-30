package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Register
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class EntryScalarSpillTest {
    private fun inspect(code: String) = EntryScalarSpill.inspect(machineCode(code), Register(7, 4))

    @Test
    fun derivesPrivateStorageWithDifferentFrames() {
        assertEquals(EntryScalarSpill.Proof(8, -24, 4), inspect("55 48 89 e5 48 83 ec 20 89 7d f0"))
        assertEquals(EntryScalarSpill.Proof(4, -8, 4), inspect("48 83 ec 18 89 7c 24 10"))
        assertEquals(
            EntryScalarSpill.Proof(13, -280, 4),
            inspect("41 54 49 89 e4 48 81 ec 00 08 00 00 90 41 89 bc 24 f0 fe ff ff")
        )
    }

    @Test
    fun rejectsArgumentChangesEscapesAndFrameOverlap() {
        for (code in listOf(
            "55 48 89 e5 89 7d 00", // Saved RBP.
            "55 48 89 e5 48 83 ec 20 89 7d fe", // Partial saved-register overlap.
            "55 48 89 e5 48 83 ec 20 89 7d 08", // Return address.
            "55 48 89 e5 48 83 ec 20 89 7d dc", // Below allocation.
            "55 48 89 e5 48 83 ec 20 89 75 f0", // Wrong original argument.
            "55 48 89 e5 48 83 ec 20 40 88 7d f0", // Wrong store width.
            "55 48 89 e5 48 83 ec 20 89 78 08", // External pointer.
            "55 48 89 e5 48 83 ec 20 31 ff 89 7d f0", // Modified argument.
            "55 48 89 e5 48 83 ec 20 e8 00 01 00 00 89 7d f0", // Prior call.
            "55 48 89 e5 48 83 ec 20 74 00 89 7d f0", // Prior branch.
            "57 48 83 ec 20 89 7c 24 08", // Saved argument outside modeled capture.
        )) assertFails(code) { inspect(code) }
    }
}
