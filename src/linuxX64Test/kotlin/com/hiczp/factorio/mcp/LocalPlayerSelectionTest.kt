package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertFails

class LocalPlayerSelectionTest {
    // Synthetic member locations; the verifier uses decoded values and control flow, never these bytes.
    private val code = "48 39 f7 74 50 55 48 89 e5 eb 10 31 c9 48 39 c1 74 41 " +
            "48 83 c7 08 48 39 f7 74 36 48 8b 07 48 8b 48 10 48 8b 91 18 00 00 00 " +
            "48 85 d2 74 e4 48 8b 8a 20 00 00 00 48 85 c9 75 d3 " +
            "48 8b 8a 28 00 00 00 48 85 c9 74 c5 48 8b 49 30 48 85 c9 75 be eb ba 31 c0 5d c3 31 c0 c3"

    private fun analyze(bytes: String, direct: Long = 32, view: Long = 40, indirect: Long = 48) =
        LocalPlayerSelection.analyze(machineCode(bytes), 128, direct, view, indirect)

    @Test
    fun verifiesTheNativePriorityAcrossEveryNullAndPlayerIdentityCase() {
        analyze(code)
        analyze(
            code.replace("8a 20 00 00 00", "8a 40 00 00 00")
                .replace("8a 28 00 00 00", "8a 50 00 00 00").replace("49 30", "49 38"), 64, 80, 56
        )
    }

    @Test
    fun rejectsChangedPriorityIdentityStrideOwnershipAndFrame() {
        for (changed in listOf(
            code.replace("75 d3", "74 d3"), // Wrong direct-player preference.
            code.replace("74 41", "75 41"), // Returns a different player's candidate.
            code.replace("83 c7 08", "83 c7 10"), // Wrong pointer stride.
            code.replace("8b 48 10", "8b 48 7c"), // Player member exceeds object bounds.
            code.replace("8a 28 00 00 00", "8a 38 00 00 00"), // Untyped Game member.
            code.replace("8b 49 30", "8b 49 38"), // Untyped GameView member.
            code.replace("48 8b 07", "48 89 07"), // Memory mutation.
            code.replace("5d c3", "90 c3"), // Lost saved frame.
            code.replace("74 50", "74 51"), // Branch enters an instruction.
        )) assertFails(changed) { analyze(changed) }
    }
}
