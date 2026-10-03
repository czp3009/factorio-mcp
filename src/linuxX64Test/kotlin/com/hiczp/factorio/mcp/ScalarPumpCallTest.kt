package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ScalarPumpCallTest {
    @Test
    fun unsignedWideMultiplyRequiresAnExplicitOptInAndCannotReachPointerAnalyses() {
        val bytes = machineCode("48 f7 e6 c3")
        assertFails { X64Instructions(bytes).all(128) }
        assertFails { X64Instructions(bytes, allowWideMultiply = true).all(128) }
        val instructions = X64Instructions(bytes, allowUnsignedWideMultiply = true).all(128)
        assertEquals(X64Instructions.Operation.MULTIPLY_WIDE, instructions.first().operation)
        assertEquals(0, instructions.first().control)
        val flow = X64ControlFlow(instructions)
        assertFails { SysVArgumentFlow(flow) }
        assertFails { SysVLocalArgument(flow) }
        assertEquals(
            0,
            X64JumpTables.resolve(instructions, 0) { _, _ -> error("Unexpected table read") }.size,
        )
    }

    private fun inspect(code: String) =
        ScalarPumpCall.inspect(X64ControlFlow(X64Instructions(machineCode(code)).all(128)), 256)

    @Test
    fun recognizesBothBooleanArgumentsAndIntegerStatusUses() {
        assertEquals(
            ScalarPumpCall.Proof(0, 2, 7, 0),
            inspect("31 ff e8 f9 00 00 00 85 c0 75 01 90 c3"),
        )
        assertEquals(
            ScalarPumpCall.Proof(0, 5, 10, 1),
            inspect("bf 01 00 00 00 e8 f6 00 00 00 83 f8 09 77 01 90 c3"),
        )
        assertEquals(
            ScalarPumpCall.Proof(0, 2, 7, 0),
            inspect("31 ff e8 f9 00 00 00 85 c0 90 75 01 90 c3"),
        )
    }

    @Test
    fun acceptsIndependentSetupInstructionsAndReallocatedScalarStatus() {
        val instructions =
            listOf(
                Instruction(0, 1, Operation.MOV, Register(1, 4), Immediate(1)),
                Instruction(1, 1, Operation.MOV, Register(6, 4), Immediate(123)),
                Instruction(2, 1, Operation.MOV, Register(7, 4), Register(1, 4)),
                Instruction(
                    3,
                    1,
                    Operation.LEA,
                    Register(11, 8),
                    Memory(null, null, 1, 1234, 8, true),
                ),
                Instruction(4, 1, Operation.CALL, Immediate(256)),
                Instruction(5, 1, Operation.MOV, Register(10, 4), Register(0, 4)),
                Instruction(6, 1, Operation.XOR, Register(0, 4), Register(0, 4)),
                Instruction(7, 1, Operation.TEST, Register(10, 4), Register(10, 4)),
                Instruction(8, 1, Operation.MOV, Register(9, 4), Immediate(7)),
                Instruction(9, 1, Operation.JCC, Immediate(11), condition = 5),
                Instruction(10, 1, Operation.NOP),
                Instruction(11, 1, Operation.RET),
            )
        assertEquals(
            ScalarPumpCall.Proof(2, 4, 7, 1),
            ScalarPumpCall.inspect(X64ControlFlow(instructions), 256),
        )
        for ((site, replacement) in
            listOf(
                0L to instructions[0].copy(source = Register(8, 4)),
                3L to
                    instructions[3].copy(
                        operation = Operation.CALL,
                        destination = Immediate(1024),
                        source = null,
                    ),
                5L to instructions[5].copy(source = Register(2, 4)),
                8L to instructions[8].copy(operation = Operation.ADD),
            )) assertFails {
            ScalarPumpCall.inspect(
                X64ControlFlow(instructions.map { if (it.offset == site) replacement else it }),
                256,
            )
        }
    }

    @Test
    fun rejectsAmbiguousEntryAndWrongScalarWidths() {
        for (code in
            listOf(
                "31 f6 e8 f9 00 00 00 85 c0 75 01 90 c3", // Wrong argument register.
                "bf 02 00 00 00 e8 f6 00 00 00 83 f8 09 77 01 90 c3", // Nonboolean.
                "31 ff e8 f9 00 00 00 84 c0 75 01 90 c3", // Byte return use.
                "31 ff e8 f9 00 00 00 85 c0 83 c1 01 75 01 90 c3", // Status flags clobbered.
                "31 ff e8 fa 00 00 00 85 c0 75 01 90 c3", // Wrong target.
                "74 02 31 ff e8 f7 00 00 00 85 c0 75 01 90 c3", // Branch skips setup.
                "74 07 31 ff e8 f7 00 00 00 85 c0 75 01 90 c3", // Branch skips call.
                "74 09 31 ff e8 f7 00 00 00 85 c0 75 01 90 c3", // Branch skips status use.
            )) assertFails(code) { inspect(code) }
    }
}
