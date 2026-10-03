package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PackedByteMaskTest {
    private fun decode(vararg bytes: Int) =
        X64Instructions(BinaryView(bytes.map(Int::toByte).toByteArray())).decode(0)

    @Test
    fun decodesOnlyTheSupportedSseRegisterMaskForm() {
        val ordinary = decode(0x66, 0x0f, 0xd7, 0xc8)
        assertEquals(Operation.VECTOR_BYTE_MASK, ordinary.operation)
        assertEquals(Register(1, 4), ordinary.destination)
        assertEquals(Register(16, 16), ordinary.source)
        assertEquals(4, ordinary.size)
        val extended = decode(0x66, 0x45, 0x0f, 0xd7, 0xcf)
        assertEquals(Register(9, 4), extended.destination)
        assertEquals(Register(31, 16), extended.source)
        assertEquals(5, extended.size)
        assertFails { decode(0x0f, 0xd7, 0xc8) }
        assertFails { decode(0x66, 0x0f, 0xd7, 0x08) }
        assertFails { decode(0x66, 0x48, 0x0f, 0xd7, 0xc8) }
        assertFails { decode(0xf3, 0x0f, 0xd7, 0xc8) }
        assertFails { decode(0x66, 0x0f, 0xd7) }
    }
}
