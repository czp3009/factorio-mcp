package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LocalArgumentReachabilityTest {
    @Test
    fun rejectsImplicitWritesOnlyWhenTheyCanReachTheObservedPoint() {
        // The later path changes RAX/RDX implicitly. It cannot change the earlier call input.
        val instructions =
            X64Instructions(
                    machineCode(
                        "53 48 83 ec 20 48 8d 7c 24 10 e8 00 10 00 00 48 f7 e6 48 83 c4 20 5b c3"
                    ),
                    allowUnsignedWideMultiply = true,
                )
                .all()
        val flow = X64ControlFlow(instructions)
        val call = instructions.single { it.operation == Operation.CALL }.offset
        assertEquals(-24L, SysVLocalArgument(flow.reaching(call)).argument(call, 7, 16))
        assertFails { SysVLocalArgument(flow) }
        // A backedge brings the implicit writes onto an incoming path of the same observation.
        val loop =
            X64Instructions(
                    machineCode("53 48 83 ec 20 48 8d 7c 24 10 e8 00 10 00 00 48 f7 e6 eb f6"),
                    allowUnsignedWideMultiply = true,
                )
                .all()
        assertFails { SysVLocalArgument(X64ControlFlow(loop).reaching(call)) }
    }
}
