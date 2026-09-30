package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ScalarOutputSliceTest {
    private fun evaluate(
        input: Int,
        middle: String = "83 f9 08 75 02 89 ca",
        beforeCall: String = "90",
        stores: String = "89 53 04 89 43 0c",
    ): List<Long> {
        val flow = X64ControlFlow(
            X64Instructions(
                machineCode(
                    "55 48 89 e5 48 83 ec 10 89 f7 48 89 75 f8 $beforeCall e8 00 01 00 00 " +
                            "48 8b 4d f8 89 c2 $middle 31 c0 $stores 48 83 c4 10 5d c3"
                )
            ).all()
        )
        val start = flow.instructions.single { it.operation == Operation.MOV && it.destination == Register(7, 4) }
        val call = flow.instructions.first { it.operation == Operation.CALL }
        val outputs =
            flow.instructions.filter { it.operation == Operation.MOV && (it.destination as? Memory)?.base == 3 }
                .map { it.offset }.toSet()
        val values =
            ScalarOutputSlice.evaluate(flow, start.offset, Register(6, 4), input, call.offset, { it + 100 }, outputs)
        return outputs.map { values.getValue(it) }
    }

    @Test
    fun followsControlOverridesThroughPrivateSpillsAndIgnoresUnrelatedUnknownValues() {
        assertEquals(listOf(8L, 0L), evaluate(8))
        assertEquals(listOf(197L, 0L), evaluate(97))
        assertEquals(listOf(197L, 0L), evaluate(97, middle = "85 f6 0f 44 f9"))
        assertEquals(listOf(197L, 0L), evaluate(97, stores = "89 53 34 89 43 28"))
    }

    @Test
    fun rejectsUnknownControlFlowOutputsCallsEscapesAndChangedCallArguments() {
        assertFails { evaluate(97, middle = "85 f6 75 00") }
        assertFails { evaluate(97, middle = "89 f2") }
        assertFails { evaluate(97, middle = "e8 00 01 00 00") }
        assertFails { evaluate(97, beforeCall = "48 8d 75 f8") }
        assertFails { evaluate(97, beforeCall = "48 8d 45 f8 48 89 03") }
        assertFails { evaluate(97, beforeCall = "83 c7 01") }
        assertFails { evaluate(97, middle = "eb fe") }
        assertFails { evaluate(97, middle = "83 c1 01 75 00") }
        assertFails { evaluate(97, beforeCall = "88 45 fa", middle = "89 ca") }
    }
}
