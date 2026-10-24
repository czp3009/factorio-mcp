package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeAllocationResultTest {
    @Test
    fun followsAllocatorResultsThroughReorderedBlocksAndRejectsClobbers() {
        val body =
            listOf(
                Instruction(0, 1, Operation.CALL, Immediate(1000)),
                Instruction(1, 1, Operation.MOV, Register(3, 8), Register(0, 8)),
                Instruction(2, 1, Operation.TEST, Register(2, 8), Register(2, 8)),
                Instruction(3, 1, Operation.JCC, Immediate(8), condition = 4),
                Instruction(4, 1, Operation.NOP),
                Instruction(5, 1, Operation.JMP, Immediate(6)),
                Instruction(6, 1, Operation.MOV, Memory(3, null, 1, 8, 1), Immediate(1)),
                Instruction(7, 1, Operation.RET),
                Instruction(8, 1, Operation.NOP),
                Instruction(9, 1, Operation.JMP, Immediate(6)),
            )
        fun verify(changed: List<Instruction> = body, register: Int = 3) =
            NativeAllocationResult.verify(X64ControlFlow(changed), changed[0], changed[6], register)
        verify()
        for (replacement in
            listOf(
                body[1].copy(source = Register(7, 8)),
                body[1].copy(source = Register(0, 4)),
                body[1].copy(operation = Operation.LEA, source = Memory(0, null, 1, 8, 8)),
            )) assertFailsWith<IllegalArgumentException> {
            verify(body.map { if (it.offset == 1L) replacement else it })
        }
        assertFailsWith<IllegalArgumentException> {
            verify(
                body.map {
                    if (it.offset == 4L)
                        it.copy(operation = Operation.CALL, destination = Immediate(2000))
                    else it
                },
                0,
            )
        }
        for (operation in listOf(Operation.MULTIPLY_WIDE, Operation.BYTE_COMPARE_EXCHANGE)) {
            assertFailsWith<IllegalArgumentException> {
                verify(
                    body.map {
                        if (it.offset == 4L)
                            it.copy(operation = operation, destination = Register(1, 8))
                        else it
                    },
                    0,
                )
            }
        }
    }

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
