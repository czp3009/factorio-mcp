package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import com.hiczp.factorio.mcp.X64Instructions.Register
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PrivateValueCopiesTest {
    @Test
    fun retainsCompleteScalarArgumentsOnlyInPrivateUnchangedStorage() {
        fun argument(middle: String, load: String = "8b 75 fc"): Int {
            val flow = X64ControlFlow(
                X64Instructions(
                    machineCode(
                        "55 48 89 e5 48 83 ec 10 89 55 fc $middle $load 48 83 c4 10 5d c3"
                    )
                ).all()
            )
            val copies = PrivateValueCopies(flow, emptyMap(), scalarArguments = mapOf(2 to 4, 1 to 4))
            val restore = flow.instructions.last { it.operation == Operation.ADD }
            return copies.argument(restore.offset, Register(6, 4))
        }
        assertEquals(2, argument("e8 00 01 00 00"))
        assertEquals(2, argument("90"))
        assertFails { argument("c6 45 ff 00") }
        assertFails { argument("48 8d 7d fc e8 00 01 00 00") }
        assertFails { argument("89 4d fe") }
        assertFails { argument("90", "0f b7 75 fc") }
        assertFails { argument("e8 00 01 00 00", "89 d6") }
    }

    private fun analyze(middle: String, load: String = "8b 47 14"): InlineArgumentFields.Field {
        val flow = X64ControlFlow(
            X64Instructions(
                machineCode(
                    "55 48 89 e5 48 83 ec 10 $load 89 45 fc $middle 8b 45 fc 48 83 c4 10 5d c3"
                )
            ).all()
        )
        val source = flow.instructions.first {
            it.operation in listOf(Operation.MOV, Operation.MOVZX) && it.source is X64Instructions.Memory
        }
        val result = PrivateValueCopies(
            flow, mapOf(
                source.offset to
                        PrivateValueCopies.Read(source.offset, InlineArgumentFields.Field(20, 4))
            )
        )
        val restore = flow.instructions.last { it.operation == Operation.ADD }
        return result.field(restore.offset, Register(0, 4)).also { assertEquals(source.offset, it.source) }.field
    }

    @Test
    fun keepsUnexposedScalarSpillsAcrossCallsAndRejectsChangedBytes() {
        assertEquals(InlineArgumentFields.Field(20, 4), analyze("e8 00 01 00 00"))
        assertEquals(InlineArgumentFields.Field(20, 4), analyze("90"))
        assertFails { analyze("c6 45 fe 00") }
        assertFails { analyze("48 8d 7d fc e8 00 01 00 00") }
        assertFails { analyze("48 8d 45 fc 48 89 06") }
        assertFails { analyze("90", load = "0f b6 47 14") }
    }

    @Test
    fun doesNotMergeEqualOffsetsFromDifferentObjects() {
        val flow = X64ControlFlow(X64Instructions(machineCode("8b 47 14 8b 4e 14 85 d2 0f 44 c1 c3")).all())
        val reads = flow.instructions.filter { it.operation == Operation.MOV }
        val field = InlineArgumentFields.Field(20, 4)
        fun copies(secondSource: Long) = PrivateValueCopies(
            flow, mapOf(
                reads[0].offset to PrivateValueCopies.Read(1, field),
                reads[1].offset to PrivateValueCopies.Read(secondSource, field),
            )
        ).field(flow.instructions.last().offset, Register(0, 4))
        assertEquals(PrivateValueCopies.Read(1, field), copies(1))
        assertFails { copies(2) }
    }
}
