package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class OwnedMemberSizeTest {
    private val code = "53 41 54 50 48 89 fb 4c 8b 63 20 4d 85 e4 74 00 " +
            "4c 89 e7 e8 00 00 00 00 90 be 40 00 00 00 4c 89 e7 e8 00 00 00 00 58 41 5c 5b c3"

    private fun flow(bytes: String = code, bypassDestruction: Boolean = false): X64ControlFlow {
        val instructions = X64Instructions(machineCode(bytes)).all()
        val calls = instructions.filter { it.operation == Operation.CALL }
        val cleanup = instructions.first { it.operation == Operation.POP }.offset
        return X64ControlFlow(instructions.map { instruction ->
            when {
                instruction == calls[0] -> instruction.copy(destination = Immediate(0x1000))
                instruction == calls[1] -> instruction.copy(destination = Immediate(0x2000))
                instruction.operation == Operation.JCC -> instruction.copy(destination = Immediate(
                    if (bypassDestruction) calls[1].offset - 3 else cleanup
                ))
                else -> instruction
            }
        })
    }

    @Test
    fun derivesTypedOwnershipAcrossRegisterCopiesAndUnrelatedInstructions() {
        assertEquals(OwnedObjectSize(32, 64), OwnedMemberSize.analyze(flow(), 0x1000, 0x2000, 128))
        val changedRegisters = code.replace("41 54", "41 55").replace("4c 8b 63", "4c 8b 6b")
            .replace("4d 85 e4", "4d 85 ed").replace("4c 89 e7", "4c 89 ef").replace("41 5c", "41 5d")
        assertEquals(OwnedObjectSize(32, 64), OwnedMemberSize.analyze(flow(changedRegisters), 0x1000, 0x2000, 128))
    }

    @Test
    fun rejectsUnprovenIdentityBoundsAndDestructionOrdering() {
        assertFails { OwnedMemberSize.analyze(flow(), 0x1000, 0x2000, 32) }
        assertFails { OwnedMemberSize.analyze(flow(bypassDestruction = true), 0x1000, 0x2000, 128) }
        for (changed in listOf(
            code.replace("4c 8b 63 20", "44 8b 63 20"),
            code.replace("be 40 00 00 00", "be ff ff ff 7f"),
            code.replace("90 be", "4c 8b 63 20 be"),
            code.replace("4c 89 e7 e8", "48 89 c7 e8"),
        )) assertFails { OwnedMemberSize.analyze(flow(changed), 0x1000, 0x2000, 128) }
    }
}
