package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SysVEntryBranchTest {
    @Test
    fun followsBothScopeGuardPathsBeforeSpecializingTheUnchangedArgument() {
        val body =
            "55 48 89 e5 48 83 ec 10 48 89 7d f8 48 83 7f 10 00 48 8d 45 f0 " +
                "75 08 48 8b 4d f8 48 89 41 10 4c 8b 6e 08 49 83 fd 02 72 06 " +
                "b8 01 00 00 00 c3 48 8b 7d f8 48 8b 7f 10 c3"
        val argument = ArgumentScalar(6, 8, 8, 0)
        assertEquals(
            SysVEntryBranch.Proof(39, 47),
            SysVEntryBranch.resolve(machineCode(body), argument),
        )
        val flow = SysVReceiverFlow(machineCode(body), 0x1000, 32, argument = argument)
        assertEquals(SysVReceiverFlow.Receiver(), flow.observeBefore(51)[7])
        assertFails { flow.before(51) }
        assertFails {
            SysVEntryBranch.resolve(
                machineCode(body.replace("48 89 41 10", "48 89 46 10")),
                argument,
            )
        }
        assertFails {
            SysVEntryBranch.resolve(machineCode(body.replace("75 08", "75 fe")), argument)
        }
    }

    private val code =
        "55 48 89 e5 48 83 ec 10 48 89 7d f8 4c 8b 6e 08 49 83 fd 02 72 06 b8 01 00 00 00 c3 48 8b 7d f8 48 8b 7f 10 c3"

    @Test
    fun specializesOnlyTheVerifiedEntryArgument() {
        assertEquals(
            SysVEntryBranch.Proof(20, 28),
            SysVEntryBranch.resolve(machineCode(code), ArgumentScalar(6, 8, 8, 0)),
        )
        assertEquals(
            SysVEntryBranch.Proof(20, 22),
            SysVEntryBranch.resolve(machineCode(code), ArgumentScalar(6, 8, 8, 2)),
        )
        assertFails { SysVEntryBranch.resolve(machineCode(code), ArgumentScalar(7, 8, 8, 0)) }
        assertFails { SysVEntryBranch.resolve(machineCode(code), ArgumentScalar(6, 0, 8, 0)) }
        assertFails { SysVEntryBranch.resolve(machineCode(code), ArgumentScalar(6, 8, 4, 0)) }
        val flow =
            SysVReceiverFlow(machineCode(code), 0x1000, 32, argument = ArgumentScalar(6, 8, 8, 0))
        assertEquals(true, flow.requiresEdge(32, 20, 28))
        assertEquals(false, 22L in flow.reachable)
        assertEquals(SysVReceiverFlow.Receiver(), flow.before(32)[7])
    }

    @Test
    fun rejectsCallsWritesOutsideTheFrameAndUnprovenConditions() {
        val argument = ArgumentScalar(6, 8, 8, 0)
        for (invalid in
            listOf(
                code.replace("48 89 7d f8", "48 89 7e 08"),
                code.replace("49 83 fd 02", "49 83 fc 02"),
                code.replace("49 83 fd 02", "49 83 fd 02 31 c0"),
                "e8 00 00 00 00 $code",
                code.replace("72 06", "72 04"),
            )) {
            assertFails { SysVReceiverFlow(machineCode(invalid), 0x1000, 32, argument = argument) }
        }
    }
}
