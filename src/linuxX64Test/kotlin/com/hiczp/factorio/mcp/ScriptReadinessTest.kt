package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ScriptReadinessTest {
    private fun fixture() =
        "55 48 89 e5 48 83 ec 10 0f b6 47 20 88 45 ff 0f b6 47 21 88 45 fe c6 47 20 01 c6 47 21 00 e8 5d 00 00 00 c3"
            .split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun resolvesIndependentlySavedByteFlagsBeforeTheNamedCall() {
        val bytes = fixture()
        assertEquals(ScriptReadiness(32, 33), SysVScopedFlags.analyze(BinaryView(bytes), 0, 128, 80))
        bytes[25] = 0
        bytes[29] = 1
        assertEquals(ScriptReadiness(33, 32), SysVScopedFlags.analyze(BinaryView(bytes), 0, 128, 80))
    }

    @Test
    fun rejectsUnprovenOriginalsPartialMembersAndIncorrectCalls() {
        val bytes = fixture()
        fun fails(index: Int, value: Int) = assertFails("Mutation at byte $index") {
            SysVScopedFlags.analyze(BinaryView(bytes.copyOf().also { it[index] = value.toByte() }), 0, 128, 80)
        }
        fails(11, 34) // Saved value came from another member.
        fails(18, 32) // Both saved values came from one member.
        fails(24, 80) // The overwritten byte exceeds the receiver.
        fails(25, 2) // Not a native boolean value.
        fails(28, 32) // A flag is overwritten twice.
        fails(31, 92) // Call does not reach the selected callee.
        fails(14, 1) // Original byte is written above the saved frame.
        fails(9, 0xb7) // Word read cannot establish a byte flag.
    }
}
