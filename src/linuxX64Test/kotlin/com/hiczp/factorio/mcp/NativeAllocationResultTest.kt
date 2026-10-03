package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeAllocationResultTest {
    private fun flow(code: String, targets: List<Long> = listOf(0x1000)): X64ControlFlow {
        var call = 0
        val instructions =
            X64Instructions(machineCode(code)).all().map { instruction ->
                if (instruction.operation == Operation.CALL) {
                    instruction.copy(destination = Immediate(targets[call++]))
                } else instruction
            }
        require(call == targets.size)
        return X64ControlFlow(instructions)
    }

    @Test
    fun resolvesAllocationAmountsWithoutAdjacentSetupOrOneRegisterChoice() {
        for (amount in
            listOf(
                "bf 60 00 00 00 90",
                "b8 60 00 00 00 90 48 89 c7",
                "48 bf 60 00 00 00 00 00 00 00",
                "85 c0 74 07 bf 60 00 00 00 eb 05 bf 60 00 00 00",
            )) {
            val body = flow("$amount e8 00 10 00 00 c3")
            assertEquals(
                body.instructions.single { it.operation == Operation.CALL },
                NativeAllocationResult.find(body, 0x1000, 96),
            )
        }
        val saved =
            flow(
                "41 bc 60 00 00 00 e8 00 10 00 00 44 89 e7 e8 00 10 00 00 c3",
                listOf(0x2000, 0x1000),
            )
        assertEquals(
            saved.instructions.last { it.operation == Operation.CALL },
            NativeAllocationResult.find(saved, 0x1000, 96),
        )
    }

    @Test
    fun rejectsBypassesChangedAmountsClobbersAndAmbiguousAllocations() {
        for (amount in
            listOf(
                "bf 61 00 00 00",
                "40 b7 60",
                "bf 60 00 00 00 83 c7 01",
                "85 c0 74 05 bf 60 00 00 00",
                "85 c0 74 07 bf 60 00 00 00 eb 05 bf 61 00 00 00",
            )) {
            assertFailsWith<IllegalStateException> {
                NativeAllocationResult.find(flow("$amount e8 00 10 00 00 c3"), 0x1000, 96)
            }
        }
        val clobbered =
            flow("bf 60 00 00 00 e8 00 10 00 00 e8 00 10 00 00 c3", listOf(0x2000, 0x1000))
        assertFailsWith<IllegalStateException> {
            NativeAllocationResult.find(clobbered, 0x1000, 96)
        }
        val ambiguous =
            flow(
                "bf 60 00 00 00 e8 00 10 00 00 bf 60 00 00 00 e8 00 10 00 00 c3",
                listOf(0x1000, 0x1000),
            )
        assertFailsWith<IllegalStateException> {
            NativeAllocationResult.find(ambiguous, 0x1000, 96)
        }
    }
}
