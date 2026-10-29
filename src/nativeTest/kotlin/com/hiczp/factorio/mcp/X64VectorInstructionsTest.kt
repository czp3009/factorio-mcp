package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Memory
import com.hiczp.factorio.mcp.X64Instructions.Operation
import com.hiczp.factorio.mcp.X64Instructions.Register
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class X64VectorInstructionsTest {
    private fun decode(bytes: String) = X64Instructions(
        BinaryView(bytes.split(' ').map { it.toInt(16).toByte() }.toByteArray())
    ).all().single()

    @Test
    fun decodesEquivalentIntegerAndDoubleHighQuadwordInterleaving() {
        for (operands in listOf("c1", "47 20", "84 ca 80 00 00 00")) {
            val integer = decode("66 0f 6d $operands")
            assertEquals(decode("66 0f 15 $operands"), integer)
            assertEquals(Operation.VECTOR_UNPACK_HIGH_QWORDS, integer.operation)
            assertEquals(Register(16, 16), integer.destination)
        }
        val registers = decode("66 45 0f 6d c1")
        assertEquals(Register(24, 16), registers.destination)
        assertEquals(Register(25, 16), registers.source)
        val memory = decode("66 45 0f 6d 47 20")
        assertEquals(Register(24, 16), memory.destination)
        assertEquals(Memory(15, null, 1, 32, 16), memory.source)
        assertEquals(6, memory.size)
    }

    @Test
    fun rejectsUnsupportedHighQuadwordPrefixesAndIncompleteOperands() {
        for (bytes in listOf(
            "0f 6d c1",
            "f2 0f 6d c1",
            "f3 0f 6d c1",
            "f0 66 0f 6d 07",
            "66 48 0f 6d c1",
            "66 0f 6d",
            "66 0f 6d 47",
        )) assertFails(bytes) { decode(bytes) }
    }
}
