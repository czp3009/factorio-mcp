@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LuaAllocationTest {
    @Test
    fun matchesCompilerGeneratedAllocationAndFieldSizes() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/lua_allocation_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun expected(name: String): Long =
                image.virtualBytes(image.symbol("fixture_$name").address, 8).unsigned(0, 8)
            assertEquals(
                LuaAllocation(
                    expected("size"), expected("global"), expected("global_offset"),
                    expected("allocator"), expected("userdata"), expected("main")
                ), SysVLuaAllocation.resolve(image)
            )
        }
    }

    // Synthetic allocation prefix with an embedded global object and three identity fields.
    private fun fixture() = ("53 41 54 41 55 49 89 fc 49 89 f5 " +
            "b9 00 01 00 00 ba 08 00 00 00 48 89 f7 31 f6 41 ff d4 " +
            "48 85 c0 74 40 48 89 c3 48 8d 80 80 00 00 00 " +
            "48 89 43 18 4c 89 a3 80 00 00 00 4c 89 ab 88 00 00 00 " +
            "48 89 9b 90 00 00 00 e8 00 00 00 00").split(' ').map { it.toInt(16).toByte() }
        .toByteArray().copyOf(128)

    @Test
    fun derivesAllocationAndEmbeddedIdentityMembers() {
        val bytes = fixture()
        assertEquals(LuaAllocation(256, 24, 128, 0, 8, 16), SysVLuaAllocation.analyze(BinaryView(bytes)))
        // Change allocation size independently of its internal layout.
        bytes[12] = 0x40
        assertEquals(320, SysVLuaAllocation.analyze(BinaryView(bytes)).size)
    }

    @Test
    fun rejectsWrongArgumentsNullGuardAndIdentityWrites() {
        val bytes = fixture()
        fun changed(index: Int, value: Int) = bytes.copyOf().also { it[index] = value.toByte() }
        fun rejects(value: ByteArray) = assertFails { SysVLuaAllocation.analyze(BinaryView(value)) }
        rejects(changed(28, 0xd5)) // Call userdata instead of the allocator.
        rejects(changed(23, 0xff)) // Pass the allocator instead of userdata in RDI.
        rejects(changed(32, 0x75)) // Initialization falls through on null.
        rejects(changed(33, 0x7f)) // Null branch exceeds the function bound.
        rejects(changed(47, 0x80)) // State global pointer overlaps the embedded global.
        rejects(changed(58, 0x80)) // Userdata overwrites the allocator field.
        rejects(changed(64, 0x93)) // Self-reference stores unrelated RDX.
        rejects(changed(12, 0x80).also { it[13] = 0 }) // Allocation is too small for its fields.
        rejects(bytes.copyOf(71)) // Truncated second call.
    }
}
