package com.hiczp.factorio.mcp

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
        assertFails { X64JumpTables.resolve(instructions, 0) { _, _ -> error("Unexpected table read") } }
    }

    private fun inspect(code: String) = ScalarPumpCall.inspect(
        X64ControlFlow(X64Instructions(machineCode(code)).all(128)), 256,
    )

    @Test
    fun recognizesBothBooleanArgumentsAndIntegerStatusUses() {
        assertEquals(
            ScalarPumpCall.Proof(0, 2, 7, 0),
            inspect("31 ff e8 f9 00 00 00 85 c0 75 01 90 c3")
        )
        assertEquals(
            ScalarPumpCall.Proof(0, 5, 10, 1),
            inspect("bf 01 00 00 00 e8 f6 00 00 00 83 f8 09 77 01 90 c3")
        )
    }

    @Test
    fun rejectsAmbiguousEntryAndWrongScalarWidths() {
        for (code in listOf(
            "31 f6 e8 f9 00 00 00 85 c0 75 01 90 c3", // Wrong argument register.
            "bf 02 00 00 00 e8 f6 00 00 00 83 f8 09 77 01 90 c3", // Nonboolean.
            "31 ff e8 f9 00 00 00 84 c0 75 01 90 c3", // Byte return use.
            "31 ff e8 f9 00 00 00 85 c0 90 75 01 90 c3", // No adjacent status branch.
            "31 ff e8 fa 00 00 00 85 c0 75 01 90 c3", // Wrong target.
            "74 02 31 ff e8 f7 00 00 00 85 c0 75 01 90 c3", // Branch skips setup.
            "74 07 31 ff e8 f7 00 00 00 85 c0 75 01 90 c3", // Branch skips call.
            "74 09 31 ff e8 f7 00 00 00 85 c0 75 01 90 c3", // Branch skips status use.
        )) assertFails(code) { inspect(code) }
    }
}
