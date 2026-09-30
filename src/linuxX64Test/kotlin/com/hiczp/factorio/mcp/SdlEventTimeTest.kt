package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SdlEventTimeTest {
    private fun flow(
        load: String = "8b 52 04", middle: String = "e8 00 01 00 00",
        tail: String = "90"
    ): X64ControlFlow = X64ControlFlow(
        X64Instructions(
            machineCode(
                "55 48 89 e5 48 83 ec 10 $load f2 48 0f 2a ca f2 0f 5e 0d 00 00 00 00 " +
                        "f2 0f 11 4d f8 $middle f2 0f 10 45 f8 f2 0f 11 07 $tail 48 83 c4 10 5d c3"
            )
        ).all()
    )

    private fun analyze(flow: X64ControlFlow, scale: Double = 2000.0): Double {
        val store = flow.instructions.single {
            it.operation == Operation.SCALAR_MOV &&
                    (it.destination as? X64Instructions.Memory)?.base == 7
        }
        val division = flow.instructions.single { it.operation == Operation.DOUBLE_DIVIDE }
        return SdlEventTime.analyze(flow, 0x1000, store.offset) {
            assertEquals(0x1000 + division.offset + division.size, it)
            scale
        }
    }

    @Test
    fun followsUnsignedTimestampConversionThroughPrivateSpills() {
        assertEquals(2000.0, analyze(flow()))
        assertEquals(1000.0, analyze(flow(), 1000.0))
        // The later unrelated borrow cannot change provenance at an earlier, nonreentered store.
        assertEquals(2000.0, analyze(flow(tail = "48 8d 7d f8 e8 00 01 00 00")))
    }

    @Test
    fun rejectsDifferentSdkFieldsClobbersEscapesAndInvalidScales() {
        assertFails { analyze(flow(load = "8b 52 08")) }
        assertFails { analyze(flow(load = "48 8b 52 04")) }
        assertFails { analyze(flow(middle = "c6 45 f8 00")) }
        assertFails { analyze(flow(middle = "48 8d 7d f8 e8 00 01 00 00")) }
        assertFails { analyze(flow(), 0.0) }
        assertFails { analyze(flow(), Double.NaN) }
        assertFails { analyze(flow(), Double.POSITIVE_INFINITY) }
    }
}
