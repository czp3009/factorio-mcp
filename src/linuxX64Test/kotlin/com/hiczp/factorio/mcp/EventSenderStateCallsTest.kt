package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class EventSenderStateCallsTest {
    @Test
    fun derivesStateUpdatePhaseFromEventKindsAcrossBothRoutes() {
        val valid = "53 48 89 f3 83 3b 07 74 05 e8 00 01 00 00 " +
                "ff 50 20 84 c0 75 08 e8 00 02 00 00 ff 50 18 ff 50 30 " +
                "83 3b 07 75 05 e8 00 03 00 00 5b e9 00 04 00 00"

        fun inspect(code: String, header: EventHeader = EventHeader(48, 0, 8)) =
            EventSenderStateCalls.updateOrder(
                X64ControlFlow(X64Instructions(machineCode(code)).all(128)),
                EventSenderStateCalls.Proof(listOf(9, 37), 43),
                EventSenderStateCalls.SourceDispatch(16, 14, 29),
                EventSenderStateCalls.GuiDispatch(24, 21, 26), header, setOf(3, 7)
            )
        assertEquals(
            mapOf(
                3L to EventSenderStateCalls.UpdateOrder.BeforeSource,
                7L to EventSenderStateCalls.UpdateOrder.AfterEvaluation
            ), inspect(valid)
        )
        // The result follows the selected comparison, not a remembered event-kind enumeration.
        assertEquals(
            mapOf(
                3L to EventSenderStateCalls.UpdateOrder.AfterEvaluation,
                7L to EventSenderStateCalls.UpdateOrder.BeforeSource
            ), inspect(valid.replace("3b 07", "3b 03"))
        )
        for (invalid in listOf(
            valid.replaceFirst("74 05", "75 05"), // Zero or two updates.
            valid.replace("75 05 e8", "74 05 e8"),
            valid.replaceFirst("74 05", "74 22"), // Bypasses all dispatches.
            valid.replaceFirst("74 05", "74 fe"), // Cyclic selected branch.
            valid.replaceFirst("83 3b 07", "83 3f 07"), // Reads Map, not Event.
            valid.replaceFirst("83 3b 07", "83 7b 04 07"), // Unestablished discriminator.
            valid.replace("ff 50 30", "90 90 90"), // No evaluation call.
        )) assertFails(invalid) { inspect(invalid) }
        assertFails { inspect(valid, EventHeader(48, 4, 8)) }
    }

    @Test
    fun routesOnlyTheZeroSourceResultThroughBothGuiCalls() {
        val valid = "ff 50 20 84 c0 75 08 e8 00 01 00 00 ff 50 18 ff 50 30 c3"
        fun inspect(code: String) = EventSenderStateCalls.route(
            X64ControlFlow(X64Instructions(machineCode(code)).all(128)),
            EventSenderStateCalls.SourceDispatch(16, 0, 15), EventSenderStateCalls.GuiDispatch(24, 7, 12)
        )
        inspect(valid)
        for (invalid in listOf(
            valid.replace("84 c0", "85 c0"), // EAX is not the established byte return.
            valid.replace("84 c0", "84 c9"),
            valid.replace("75 08", "74 08"), // Inverted native routing.
            valid.replace("75 08", "75 05"), // Nonzero result still runs GUI logic.
            valid.replace("75 08", "75 0b"), // Nonzero result skips evaluation.
            valid.replace("e8 00 01 00 00", "eb 06 90 90 90"), // Zero path skips both GUI calls.
            valid.replace("ff 50 18", "eb f9 90"), // Repeated native side effects.
        )) assertFails(invalid) { inspect(invalid) }
    }

    @Test
    fun verifiesGuiFallbackReceiverArgumentsAndOrdering() {
        val valid = "53 48 89 f3 48 8b 05 f5 0f 00 00 48 8b 78 10 48 89 de e8 e9 1f 00 00 " +
                "48 8b 3d e2 0f 00 00 48 8b 07 31 f6 ff 50 18 5b c3"

        fun inspect(code: String) = EventSenderStateCalls.guiDispatch(
            machineCode(code), 0x1000, 64,
            0x2000, 64, 0x3000, 3
        )
        assertEquals(EventSenderStateCalls.GuiDispatch(16, 18, 35), inspect(valid))
        for (invalid in listOf(
            valid.replace("48 89 f3", "48 89 fb"),
            valid.replace("8b 78 10", "8b 78 40"),
            valid.replace("f5 0f", "fd 0f"),
            valid.replace("e2 0f", "ea 0f"),
            valid.replace("48 8b 07", "48 8b 06"),
            valid.replace("31 f6", "31 d2"),
            valid.replace("31 f6", "be 01 00 00 00"),
            valid.replace("ff 50 18", "ff 50 20"),
        )) assertFails(invalid) { inspect(invalid) }
        // Both dispatches remain reachable, but one branch exits before completing GUI logic.
        assertFails { inspect(valid.replace("31 f6", "31 f6 74 03")) }
    }

    private val sourceCode = "53 41 54 41 55 49 89 fc 48 89 f3 " +
            "49 8b 44 24 10 48 8b 78 18 48 8b 07 48 89 de ff 50 68 " +
            "49 8b 44 24 10 48 8b 78 18 48 8b 07 ff 50 48 41 5d 41 5c 5b c3"

    private fun source(value: String) = EventSenderStateCalls.sourceDispatch(
        machineCode(value), 0x1000,
        64, 16, 64, 13, 9
    )

    @Test
    fun connectsEventAndEvaluationToTheSameMapOwnedSource() {
        assertEquals(24L, source(sourceCode).gameSource)
        for (invalid in listOf(
            sourceCode.replace("49 89 fc", "49 89 f4"),
            sourceCode.replace("48 89 f3", "48 89 fb"),
            sourceCode.replace("44 24 10", "44 24 18"),
            sourceCode.replace("8b 78 18", "8b 78 40"),
            sourceCode.replace("48 8b 07", "48 8b 06"),
            sourceCode.replace("ff 50 68", "ff 50 69"),
            sourceCode.replace("ff 50 48", "ff 50 40"),
            sourceCode.replace("ff 50 68", "ff 50 68 c3"), // Normal exit bypasses evaluation.
            sourceCode.replace("ff 50 68", "ff 50 68 85 c0 74 0f"), // Only one branch bypasses evaluation.
            sourceCode.replaceFirst(
                "49 8b 44 24 10",
                "85 c0 74 12 49 8b 44 24 10"
            ), // Event remains reachable but optional.
            sourceCode.replace("ff 50 68", "eb 01 90"), // No reachable event dispatch.
            sourceCode.replace("48 8b 07 ff 50 48", "48 8b 07 48 89 df ff 50 48"),
        )) assertFails(invalid) { source(invalid) }
        assertFails { source(sourceCode.replace("ff 50 68", "ff 50 68 49 c7 c4 00 00 00 00")) }
    }

    private val code = "53 48 89 f3 48 8b 05 f5 0f 00 00 48 8b 78 10 48 89 de e8 e9 1f 00 00 " +
            "48 8b 05 e2 0f 00 00 48 8b 78 10 48 89 de 5b e9 d5 2f 00 00"

    private fun inspect(value: String) = EventSenderStateCalls.inspect(
        machineCode(value), 0x1000, 0x3000, 0x4000,
        InputStateLayout(0x2000, 64, 16, 64), 64
    )

    @Test
    fun retainsTheOriginalEventAcrossNativeUpdateAndRestoresTailFrame() {
        assertEquals(EventSenderStateCalls.Proof(listOf(18), 38), inspect(code))
    }

    @Test
    fun rejectsChangedArgumentsServiceAndTailCleanup() {
        for (invalid in listOf(
            code.replace("48 89 f3", "48 89 fb"), // Saved Map instead of Event.
            code.replace("48 89 de", "48 89 fe"), // InputState passed as Event.
            code.replace("8b 78 10", "8b 78 18"), // Another global member.
            code.replace("f5 0f", "fd 0f"), // Another global root at update.
            code.replace("e2 0f", "ea 0f"), // Another global root at post-update.
            code.replace("5b e9", "90 e9"), // Outstanding saved frame.
            code.replace("5b e9", "5d e9"), // Corrupt caller's preserved registers.
            code.replace("e9 d5 2f", "e9 d6 2f"), // Another tail target.
            code.replace("e8 e9 1f", "e8 ea 1f"), // No established update call.
        )) assertFails(invalid) { inspect(invalid) }
    }
}
