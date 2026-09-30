package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SdlDeviceAllocationTest {
    private val code = "bf 01 00 00 00 be 60 00 00 00 ff 15 f0 0f 00 00 " +
            "48 89 c3 48 8d 05 e6 1f 00 00 48 89 43 40 c3"

    private fun inspect(code: String = this.code, allocator: Long = 0x2000, backend: Long = 0x3000) =
        SdlDeviceAllocation.analyze(
            X64ControlFlow(X64Instructions(machineCode(code)).all()),
            0x1000, allocator, backend
        )

    @Test
    fun derivesCallbackMemberFromItsOwnCallResultAndAllocationArguments() {
        assertEquals(SdlDeviceAllocation(96, 64, 0x2000, 0x3000), inspect())
        assertEquals(192, inspect(code.replace("bf 01", "bf 02")).size)
        assertEquals(72, inspect(code.replace("43 40", "43 48")).member)
        assertFails { inspect(allocator = 0x2008) }
        assertFails { inspect(backend = 0x3008) }
    }

    @Test
    fun rejectsUnknownTruncatedAdjustedOrUndersizedAllocation() {
        for ((before, after) in listOf(
            "bf 01" to "bf 00",
            "be 60" to "be 40",
            "be 60 00 00 00" to "66 be 60 00 90",
            "48 89 c3" to "48 89 cb",
            "48 89 c3" to "48 89 f3",
            "48 89 c3" to "48 8d 18",
            "48 89 c3" to "89 c3 90",
            "43 40" to "43 41",
            "43 40" to "43 60",
        )) assertFails("Mutation must reject: $before -> $after") { inspect(code.replace(before, after)) }
        // A later call clobbers RAX; it cannot be mistaken for the selected allocation result.
        assertFails {
            inspect(
                code.replace("48 89 c3", "e8 00 40 00 00 48 89 c3")
                    .replace("e6 1f", "e1 1f")
            )
        }
    }
}
