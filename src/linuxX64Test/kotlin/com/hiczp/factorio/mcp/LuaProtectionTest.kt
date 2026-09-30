@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LuaProtectionTest {
    @Test
    fun verifiesCompilerGeneratedCallbackAndNormalRestoration() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/lua_protection_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun expected(name: String) = image.virtualBytes(image.symbol("fixture_$name").address, 8).unsigned(0, 8)
            val function = image.symbol("fixture_protected")
            val bytes = image.functionBytes(function, 4096)
            val proof = SysVLuaProtection.analyze(bytes, expected("state_size"))
            assertEquals(expected("counter"), proof.counter)
            assertEquals(expected("handler"), proof.handler)
            assertEquals(expected("status"), proof.status)
            assertEquals(proof.status + 4, proof.recordSize)
            assertFails { SysVLuaProtection.analyze(bytes, 8) }
        }
    }

    private fun fixture() = ("55 48 89 e5 41 57 53 48 83 ec 10 48 89 f0 48 89 fb " +
            "44 0f b7 7f 0a c7 45 ec 00 00 00 00 48 8b 4f 18 48 89 4d e0 " +
            "48 8d 4d e0 48 89 4f 18 48 89 d6 ff d0 48 8b 45 e0 " +
            "48 89 43 18 66 44 89 7b 0a 8b 45 ec 48 83 c4 10 5b 41 5f 5d c3")
        .split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun rejectsIncorrectCallbackArgumentsStateAndSavedFrame() {
        val bytes = fixture()
        assertEquals(LuaProtection(10, 24, 12, 16), SysVLuaProtection.analyze(BinaryView(bytes), 64))
        fun fails(index: Int, value: Int) = assertFails("Mutation at byte $index") {
            SysVLuaProtection.analyze(BinaryView(bytes.copyOf().also { it[index] = value.toByte() }), 64)
        }
        fails(13, 0xd0) // Callback pointer comes from userdata.
        fails(47, 0xde) // Callback userdata comes from the state.
        fails(57, 16) // Handler is restored to the wrong field.
        fails(62, 11) // Counter is restored to the wrong field.
        fails(65, 0xe8) // Return reads an uninitialized status slot.
        fails(25, 1) // Status does not start at zero.
        fails(21, 63) // Counter read crosses the state bound.
        fails(40, 0xf8) // Record points into a pushed register slot.
    }
}
