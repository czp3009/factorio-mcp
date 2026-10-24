package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SdlEventTimeTest {
    @Test
    fun connectsTheFullClockReturnToTheSdkTimestamp() {
        fun inspect(body: String) {
            val flow = X64ControlFlow(X64Instructions(machineCode(body)).all())
            val call = flow.instructions.first { it.operation == Operation.CALL }
            SdlEventTime.verifyClock(flow, (call.destination as Immediate).value)
        }
        val prefix = "53 48 89 fb e8 00 01 00 00"
        inspect("$prefix 48 89 43 08 5b c3")
        inspect("$prefix 48 89 c1 90 48 89 4b 08 5b c3")
        assertFails { inspect("$prefix 89 43 08 5b c3") }
        assertFails { inspect("$prefix 48 89 43 10 5b c3") }
        assertFails { inspect("$prefix 89 c0 48 89 43 08 5b c3") }
        assertFails { inspect("$prefix e8 00 02 00 00 48 89 43 08 5b c3") }
    }

    private fun flow(
        load: String = "f2 0f 10 4f 08",
        middle: String = "e8 00 01 00 00",
        tail: String = "90",
    ): X64ControlFlow =
        X64ControlFlow(
            X64Instructions(
                    machineCode(
                        "55 48 89 e5 48 83 ec 10 $load 0f 14 0d 00 00 00 00 49 89 f2 " +
                            "66 0f 5c 0d 00 00 00 00 66 0f 28 c1 66 0f 15 c1 f2 0f 58 c1 " +
                            "f2 0f 5e 05 00 00 00 00 f2 0f 11 45 f8 $middle " +
                            "f2 0f 10 4d f8 f2 0f 11 0f $tail 48 83 c4 10 5d c3"
                    )
                )
                .all()
        )

    private fun analyze(
        flow: X64ControlFlow,
        scale: Double = 1000000000.0,
        lowExponent: Long = 0x43300000,
    ): Double {
        val store =
            flow.instructions.single {
                it.operation == Operation.SCALAR_MOV &&
                    (it.destination as? X64Instructions.Memory)?.base == 7
            }
        return SdlEventTime.analyze(flow, 0x1000, store.offset) { address, width ->
            val instruction = flow.instructions.single { 0x1000 + it.offset + it.size == address }
            val words =
                when (instruction.operation) {
                    Operation.VECTOR_UNPACK_LOW_FLOATS -> listOf(lowExponent, 0x45300000L, 0L, 0L)
                    Operation.VECTOR_SUBTRACT_DOUBLES -> listOf(0L, 0x43300000L, 0L, 0x45300000L)
                    Operation.DOUBLE_DIVIDE ->
                        listOf(scale.toBits() and 0xffffffffL, scale.toBits() ushr 32)
                    else -> error("Unexpected literal read")
                }
            assertEquals(words.size * 4, width)
            BinaryView(
                ByteArray(width) { byte -> (words[byte / 4] ushr ((byte % 4) * 8)).toByte() }
            )
        }
    }

    @Test
    fun followsUnsignedTimestampConversionThroughPrivateSpills() {
        assertEquals(1000000000.0, analyze(flow()))
        assertEquals(1000.0, analyze(flow(), 1000.0))
        // The later unrelated borrow cannot change provenance at an earlier, nonreentered store.
        assertEquals(1000000000.0, analyze(flow(tail = "48 8d 7d f8 e8 00 01 00 00")))
    }

    @Test
    fun rejectsDifferentSdkFieldsClobbersEscapesAndInvalidScales() {
        assertFails { analyze(flow(load = "f2 0f 10 4f 10")) }
        assertFails { analyze(flow(load = "f2 0f 10 4e 08")) }
        assertFails { analyze(flow(), lowExponent = 0x43400000) }
        assertFails { analyze(flow(middle = "c6 45 f8 00")) }
        assertFails { analyze(flow(middle = "48 8d 7d f8 e8 00 01 00 00")) }
        assertFails { analyze(flow(), 0.0) }
        assertFails { analyze(flow(), Double.NaN) }
        assertFails { analyze(flow(), Double.POSITIVE_INFINITY) }
    }
}
