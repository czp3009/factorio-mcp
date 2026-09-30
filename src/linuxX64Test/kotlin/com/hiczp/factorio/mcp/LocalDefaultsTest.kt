package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LocalDefaultsTest {
    private fun analyze(
        middle: String = "",
        initialization: String = "c7 45 e0 11 00 00 00 48 c7 45 e8 00 00 00 00"
    ): Map<Long, Int> {
        val code = listOf(
            "55 48 89 e5 48 83 ec 20", initialization, middle,
            "48 8d 75 e0 e8 00 01 00 00 48 83 c4 20 5d c3"
        ).filter { it.isNotBlank() }.joinToString(" ")
        val flow = X64ControlFlow(X64Instructions(machineCode(code)).all())
        val call = flow.instructions.last { it.operation == Operation.CALL }
        return LocalDefaults.before(
            flow, call.offset, 24,
            listOf(InlineArgumentFields.Field(0, 4), InlineArgumentFields.Field(8, 8))
        )
    }

    @Test
    fun retainsIndependentHeaderDefaultsAcrossGuardsAndUnexposedCalls() {
        val expected = ((0L..3L) + (8L..15L)).associateWith { if (it == 0L) 17 else 0 }
        assertEquals(expected, analyze())
        assertEquals(expected, analyze("85 ff 74 01 90"))
        assertEquals(expected, analyze("e8 00 01 00 00"))
        assertEquals(expected, analyze("c6 45 e1 00"))
    }

    @Test
    fun rejectsMissingClobberedDivergentEscapedAndDeallocatedBytes() {
        assertFails { analyze(initialization = "c7 45 e0 11 00 00 00") }
        assertFails { analyze("88 45 e1") }
        assertFails { analyze("85 ff 74 04 c6 45 e1 01") }
        assertFails { analyze("48 8d 75 e0 e8 00 01 00 00") }
        assertFails { analyze("48 8d 45 e0 48 89 07") }
        assertFails { analyze("48 83 c4 20 48 83 ec 20") }
    }
}
