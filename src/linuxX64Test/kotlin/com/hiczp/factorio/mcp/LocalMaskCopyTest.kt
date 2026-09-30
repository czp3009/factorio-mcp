package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlin.test.Test
import kotlin.test.assertFails

class LocalMaskCopyTest {
    private val prefix = "55 48 89 e5 48 83 ec 60 0f b7 4d f0 89 ca 66 83 f9 01 75 04 0f b7 57 10"
    private val suffix = "48 83 7f 08 00 0f 45 ca 66 89 4d c8 48 8d 75 c0 e8 00 01 00 00 48 83 c4 60 5d c3"

    private fun verify(code: String = "$prefix $suffix", masks: Set<Int> = setOf(2, 4, 8), output: Long = 8) {
        val instructions = X64Instructions(machineCode(code)).all()
        val source = instructions.first { it.operation == Operation.MOVZX }.offset
        val sink = instructions.single { it.operation == Operation.CALL }.offset
        LocalMaskCopy.verify(X64ControlFlow(instructions), source, sink, masks, output, extent = 32)
    }

    @Test
    fun provesNonfallbackMasksWithoutAssumingTheCapturedGuiState() {
        verify()
        verify(masks = setOf(16, 32, 64))
        verify(code = "${prefix.replace("66 83 f9 01", "66 83 f9 03")} $suffix")
    }

    @Test
    fun boundsTheOutgoingObjectSeparatelyFromItsLocalConstructionCopy() {
        val code = "$prefix 48 83 7f 08 00 0f 45 ca 66 89 4d c8 48 8b 45 c8 " +
                "48 89 44 24 08 e8 00 01 00 00 48 83 c4 60 5d c3"
        val instructions = X64Instructions(machineCode(code)).all()
        val source = instructions.first { it.operation == Operation.MOVZX }.offset
        val sink = instructions.single { it.operation == Operation.CALL }.offset
        val flow = X64ControlFlow(instructions)
        LocalMaskCopy.verify(flow, source, sink, setOf(2, 4, 8), 8, extent = 32, argument = 4)
        assertFails { LocalMaskCopy.verify(flow, source, sink, setOf(2), 8, extent = 64, argument = 4) }
    }

    @Test
    fun rejectsChangedCopiesFallbackCasesAndIncorrectConditionPolarity() {
        assertFails { verify(masks = setOf(1, 2, 4)) }
        assertFails { verify(masks = setOf(0)) }
        assertFails { verify(output = 9) }
        assertFails { verify(code = "${prefix.replace("75 04", "74 04")} $suffix") }
        assertFails { verify(code = "${prefix.replace("89 ca", "31 d2")} $suffix") }
        assertFails { verify(code = "$prefix ${suffix.replace("66 89 4d c8", "66 89 55 c8")}", masks = setOf(1)) }
        assertFails { verify(code = "${prefix.replace("66 83 f9 01", "66 83 f9 08")} $suffix") }
    }
}
