package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReturnedPointerOriginsTest {
    private fun result(code: String): ReturnedPointerOrigins.Value? {
        val flow = X64ControlFlow(X64Instructions(machineCode(code)).all())
        val ret = flow.instructions.single { it.operation == Operation.RET }
        return ReturnedPointerOrigins(flow).register(ret.offset, 0)
    }

    @Test
    fun exchangeAddDoesNotPreserveAnEarlierCallResult() {
        val instructions = X64Instructions(machineCode("e8 00 10 00 00 f0 48 0f c1 06 c3"),
            allowAtomicExchangeAdd = true).all()
        val flow = X64ControlFlow(instructions)
        assertNull(ReturnedPointerOrigins(flow).register(instructions.last().offset, 0))
    }

    @Test
    fun followsFullPointersAndPreservedRegistersAcrossUnrelatedCalls() {
        assertEquals(ReturnedPointerOrigins.Value(0, listOf(24)), result("e8 00 10 00 00 48 8b 40 18 c3"))
        assertEquals(ReturnedPointerOrigins.Value(0, listOf(24), 8),
            result("e8 00 10 00 00 48 89 c3 90 e8 00 20 00 00 48 8b 43 18 48 83 c0 08 c3"))
    }

    @Test
    fun losesPartialPointersAndDisagreeingBranches() {
        assertNull(result("e8 00 10 00 00 89 c0 c3"))
        assertNull(result("e8 00 10 00 00 85 c9 74 04 48 8b 40 18 c3"))
        assertNull(result("e8 00 10 00 00 48 89 c1 e8 00 20 00 00 48 89 c8 c3"))
    }
}
