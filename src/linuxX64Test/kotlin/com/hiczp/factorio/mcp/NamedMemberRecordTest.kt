package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class NamedMemberRecordTest {
    private val string = NativeStringLayout(0, 8, 16, 32, ElfImage.Symbol("fixture", 1, 1, 2, 1))
    private val body = "48 83 ec 28 49 89 f0 48 83 c7 18 " +
            "48 8d 44 24 10 48 89 04 24 48 c7 44 24 08 04 00 00 00 " +
            "c7 44 24 10 6e 61 6d 65 c6 44 24 14 00 48 89 e6 " +
            "ba 20 00 00 00 b9 08 00 00 00 e8 00 10 00 00 48 83 c4 28 c3"

    private fun analyze(code: String, size: Long = 64): NamedMemberRecord.Record {
        val flow = X64ControlFlow(X64Instructions(machineCode(code)).all())
        val consume = (flow.instructions.single { it.operation == X64Instructions.Operation.CALL }.destination
                as X64Instructions.Immediate).value
        return NamedMemberRecord.analyze(flow, consume, string, size, setOf("name")).getValue("name")
    }

    @Test
    fun associatesExactNameWithOriginalMemberAndTypedExtent() {
        val record = analyze(body)
        assertEquals("name", record.name)
        assertEquals(24L, record.offset)
        assertEquals(32L, record.width)
        assertEquals(8L, record.alignment)
    }

    @Test
    fun rejectsWrongOwnersBoundsAndWidths() {
        assertFails { analyze(body, 48) }
        assertFails { analyze(body.replace("49 89 f0", "49 89 f8")) }
        assertFails { analyze(body.replace("48 83 c7 18", "48 89 f7 48 83 c7 18")) }
        assertFails { analyze(body.replace("ba 20 00 00 00", "ba 00 00 00 00")) }
        assertFails { analyze(body.replace("b9 08 00 00 00", "b9 03 00 00 00")) }
    }
}
