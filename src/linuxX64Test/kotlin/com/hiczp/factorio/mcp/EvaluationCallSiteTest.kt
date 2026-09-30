package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class EvaluationCallSiteTest {
    @Test
    fun verifiesVirtualTailReceiverAndFrameBeforeTreatingItAsAnExit() {
        val code = "53 48 89 fb 48 8b 7b 10 48 8b 07 48 8b 40 20 5b ff e0"
        val slots = mutableListOf<Int>()
        val flow = EvaluationCallSite.flow(machineCode(code), 64, 16) { slots += it }
        assertEquals(listOf(4), slots)
        assertEquals(emptyList(), flow.successors.getValue(16))
        for (invalid in listOf(
            code.replace("5b ff", "59 ff"),
            code.replace("7b 10", "7b 18"),
            code.replace("48 8b 07", "48 8b 06"),
            code.replace("48 8b 40 20", "48 8d 40 20"),
        )) assertFails { EvaluationCallSite.flow(machineCode(invalid), 64, 16) {} }
        assertFails { EvaluationCallSite.flow(machineCode(code), 64, 16) { error("Unverified table slot") } }
    }

    @Test
    fun distinguishesTheHandlersSourceFromAnUnrelatedSameSlotCall() {
        val valid = "53 48 89 fb 48 8b 7b 18 48 8b 07 ff 50 48 " +
                "48 8b 7b 10 48 8b 07 ff 50 48 5b c3"

        fun inspect(code: String) = EvaluationCallSite.analyze(
            X64ControlFlow(X64Instructions(machineCode(code)).all(128)), 64, 16, 9
        )
        assertEquals(24L, inspect(valid))
        // A different call can merge unrelated receiver pointers. It must not become an eligible source call.
        assertEquals(
            34L, inspect(
                valid.replaceFirst(
                    "48 8b 7b 18",
                    "85 f6 74 06 48 8b 7b 18 eb 04 48 8b 7b 20"
                )
            )
        )
        for (invalid in listOf(
            valid.replace("48 89 fb", "48 89 f3"), // Receiver comes from another argument.
            valid.replace("7b 10", "7b 18"), // Selected member is not used.
            valid.replace("7b 18", "7b 10"), // Two calls through the selected member.
            valid.replace("48 8b 07", "48 8b 06"), // Different receiver's table.
            valid.replace("ff 50 48", "ff 50 40"), // Different virtual method.
        )) assertFails(invalid) { inspect(invalid) }
        assertFails {
            EvaluationCallSite.analyze(
                X64ControlFlow(X64Instructions(machineCode(valid)).all(128)), 16, 16, 9
            )
        }
    }
}
