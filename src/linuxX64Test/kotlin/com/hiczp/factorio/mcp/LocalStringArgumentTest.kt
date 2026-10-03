package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LocalStringArgumentTest {
    private val string = NativeStringLayout(0, 8, 16, 32, ElfImage.Symbol("fixture", 1, 1, 2, 1))
    private val prefix = "48 83 ec 28 48 8d 44 24 10 48 89 04 24 " +
            "48 c7 44 24 08 04 00 00 00 c7 44 24 10 6e 61 6d 65 c6 44 24 14 00 "
    private val suffix = "48 89 e6 ba 20 00 00 00 b9 08 00 00 00 e8 00 10 00 00 48 83 c4 28 c3"

    private fun analyze(code: String, verify: (LocalStringArgument, Long) -> Unit) {
        val flow = X64ControlFlow(X64Instructions(machineCode(code)).all())
        val call = flow.instructions.last { it.operation == X64Instructions.Operation.CALL }.offset
        verify(LocalStringArgument(flow, string), call)
    }

    @Test
    fun followsLiteralStorageAndZeroExtendedArgumentsAcrossUnrelatedInstructions() {
        analyze(prefix + "90 45 31 db " + suffix) { literal, call ->
            assertEquals("name", literal.text(call, 6))
            assertEquals(32L, literal.constant(call, 2))
            assertEquals(8L, literal.constant(call, 1))
        }
        analyze(prefix + "85 ff 74 05 c6 44 24 10 6e " + suffix) { literal, call ->
            assertEquals("name", literal.text(call, 6))
        }
    }

    @Test
    fun rejectsUnknownBytesAliasesCallsAndConflictingBranches() {
        for (code in listOf(
            prefix + "85 ff 74 05 c6 44 24 10 78 " + suffix,
            prefix + "48 8b 07 c6 00 00 " + suffix,
            prefix + "e8 00 10 00 00 " + suffix,
            prefix.replace("c6 44 24 14 00", "c6 44 24 14 01") + suffix,
            prefix.replace("04 00 00 00", "10 00 00 00") + suffix,
            prefix.replace("48 8d 44 24 10", "48 8d 44 24 18") + suffix,
            prefix.replace("c7 44 24 10 6e 61 6d 65", "90") + suffix,
        )) analyze(code) { literal, call -> assertFails { literal.text(call, 6) } }
    }
}
