package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class BoundedBytePointerLookupTest {
    private fun body(middle: String = "90", condition: Int = 3): Pair<X64ControlFlow, Instruction> {
        val bytes = machineCode("0f b6 47 05 48 83 f8 07 73 00 $middle 48 8d 0d ef 01 00 00 " +
                "48 8b 14 c1 c3 e8 00 01 00 00 c3")
        val decoded = X64Instructions(bytes).all()
        val failure = decoded.single { it.operation == Operation.CALL }
        val instructions = decoded.map {
            when (it.operation) {
                Operation.JCC -> it.copy(destination = Immediate(failure.offset), condition = condition)
                Operation.LEA -> it.copy(source = (it.source as Memory).copy(displacement = 4096 - it.offset - it.size))
                else -> it
            }
        }
        return X64ControlFlow(instructions) to failure
    }

    @Test
    fun derivesNativeByteTableFromUnsignedBoundAndPreservedSource() {
        for (middle in listOf("90", "be 03 00 00 00", "48 89 c6 48 89 f0")) {
            val (flow, failure) = body(middle)
            val load = flow.instructions.single { (it.source as? Memory)?.index != null }
            val result = BoundedBytePointerLookup.analyze(flow, 4096, load.offset,
                (failure.destination as Immediate).value)
            assertEquals(5L, result.field)
            assertEquals(7, result.count)
        }
    }

    @Test
    fun rejectsChangedIndicesSignedGuardsAndWrongFailureIdentity() {
        fun inspect(flow: X64ControlFlow, failure: Instruction, wrongError: Boolean = false) {
            val load = flow.instructions.single { (it.source as? Memory)?.index != null }
            BoundedBytePointerLookup.analyze(flow, 4096, load.offset,
                (failure.destination as Immediate).value + if (wrongError) 1 else 0)
        }
        val plain = body()
        assertFails { inspect(plain.first, plain.second, true) }
        for (middle in listOf("48 ff c0", "0f b6 47 06", "48 83 e0 07")) {
            val (flow, failure) = body(middle)
            assertFails { inspect(flow, failure) }
        }
        val signed = body(condition = 13)
        assertFails { inspect(signed.first, signed.second) }
        val (flow, failure) = body()
        val bypass = X64ControlFlow(flow.instructions.map { if (it.operation == Operation.JCC)
            it.copy(operation = Operation.NOP, destination = null, condition = null) else it })
        assertFails { inspect(bypass, failure) }
    }
}
