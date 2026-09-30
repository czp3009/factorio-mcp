package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LuaStackCapacityTest {
    private val layout = LuaStackLayout(24, 40, 0, 16)
    private fun fixture() =
        "55 48 89 e5 48 83 ec 10 c7 45 fc 01 00 00 00 48 8b 4f 18 48 8b 57 38 48 29 ca 48 83 fa 10 7e 01 c3 c3"
            .split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun derivesCapacityFromTheVerifiedStackStrideAndTop() {
        assertEquals(56, SysVLuaCapacity.analyze(BinaryView(fixture()), layout, 128))
        val moved = fixture().also { it[22] = 64 }
        assertEquals(64, SysVLuaCapacity.analyze(BinaryView(moved), layout, 128))
    }

    @Test
    fun rejectsWrongPointerDifferenceBoundsAndGuard() {
        val bytes = fixture()
        fun fails(index: Int, value: Int) = assertFails {
            SysVLuaCapacity.analyze(BinaryView(bytes.copyOf().also { it[index] = value.toByte() }), layout, 128)
        }
        fails(18, 32) // Subtrahend is not the independently derived top.
        fails(22, 24) // Identical end and top members.
        fails(22, 124) // Eight-byte load exceeds the state bound.
        fails(22, 25) // Overlapping, unaligned field.
        fails(25, 0xd1) // Reversed subtraction.
        fails(29, 8) // Compared width is not one Lua value.
        fails(30, 0x7f) // Reversed capacity condition.
        fails(31, 0x7f) // Guard leaves the function bound.
        fails(10, 8) // Frame initialization writes above the saved frame.
    }
}
