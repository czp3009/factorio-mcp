package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class MemberEqualityTest {
    @Test
    fun decodesSignExtendedImmediateStackArgumentsWithoutChangingReceiver() {
        val code = machineCode("6a ff 68 00 00 00 80 80 bf 20 00 00 00 00 48 83 c4 10 c3")
        val instructions = X64Instructions(code).all()
        assertEquals(X64Instructions.Operation.PUSH, instructions[0].operation)
        assertEquals(X64Instructions.Immediate(-1), instructions[0].destination)
        assertEquals(X64Instructions.Immediate(Int.MIN_VALUE.toLong()), instructions[1].destination)
        assertEquals(MemberEquality(32, 0), MemberEquality.analyze(code, 7, 14, 1))
        for (invalid in listOf("66 6a 00", "48 6a 00", "68 00", "6a")) {
            assertFails { X64Instructions(machineCode(invalid)).all() }
        }
    }

    @Test
    fun acceptsBoundedOwnersWithMoreThanDefaultInstructionCount() {
        val code = machineCode("90 ".repeat(300) + "80 bf 20 00 00 00 00 c3")
        assertEquals(MemberEquality(32, 0), MemberEquality.analyze(code, 300, 307, 1))
        assertFails { MemberEquality.analyze(machineCode("90 ".repeat(4097)), 300, 307, 1) }
    }

    @Test
    fun tracksReceiverAcrossUnrelatedScalarSubtraction() {
        val code = "53 48 89 fb f2 0f 5c 83 28 00 00 00 80 bb 20 00 00 00 00 5b c3"
        val subtraction = X64Instructions(machineCode(code)).all()[2]
        assertEquals(X64Instructions.Operation.DOUBLE_SUBTRACT, subtraction.operation)
        assertEquals(X64Instructions.Register(16, 8), subtraction.destination)
        assertEquals(X64Instructions.Memory(3, null, 1, 40, 8, false), subtraction.source)
        assertEquals(MemberEquality(32, 0), MemberEquality.analyze(machineCode(code), 12, 19, 1))
        assertFails { X64Instructions(machineCode("f2 48 0f 5c c1")).all() }
    }

    @Test
    fun acceptsOnlyEntryProloguePushesAttributedToInlineEquality() {
        val code = "55 48 89 e5 41 57 50 80 bf 20 00 00 00 00 58 41 5f 5d c3"
        assertEquals(MemberEquality(32, 0), MemberEquality.analyze(machineCode(code), 4, 14, 1))
        assertFails { MemberEquality.analyze(machineCode(code.replace("48 89 e5", "48 89 f7")), 4, 14, 1) }
        assertFails { MemberEquality.analyze(machineCode(code.replace("41 57 50", "90 90 50")), 6, 13, 1) }
    }

    @Test
    fun verifiesByteWidthOriginalReceiverAndInlineBoundaries() {
        val code = "53 48 89 fb 80 bb 20 00 00 00 00 75 00 5b c3"
        assertEquals(MemberEquality(32, 0), MemberEquality.analyze(machineCode(code), 4, 11, 1))
        assertFails { MemberEquality.analyze(machineCode(code), 4, 11, 4) }
        assertFails { MemberEquality.analyze(machineCode(code), 4, 10, 1) }
        assertFails { MemberEquality.analyze(machineCode(code.replace("89 fb", "89 f3")), 4, 11, 1) }
        assertFails { MemberEquality.analyze(machineCode(code.replace("20 00 00 00", "ff ff ff ff")), 4, 11, 1) }
    }
}
