@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertFails

class LuaStringPushTest {
    @Test
    fun verifiesCompilerGeneratedCollectorAndDirectPaths() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/lua_string_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            val entry = image.symbol("fixture_push")
            val code = image.functionBytes(entry, 8192)
            val size = image.symbol("fixture_state_size").let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val construct = image.symbol("fixture_construct").address
            val collect = image.symbol("fixture_collect").address
            SysVLuaStringPush.analyze(code, entry.address, construct, collect, size)
            assertFails { SysVLuaStringPush.analyze(code, entry.address, construct, collect + 1, size) }
            assertFails { SysVLuaStringPush.analyze(code, entry.address, construct + 1, collect, size) }
        }
    }

    @Test
    fun rejectsChangedArgumentsAndPathsBypassingConstruction() {
        // One aligned external call followed by an ordinary return; the return register is intentionally unused.
        val valid = "53 48 89 fb e8 f7 00 00 00 5b c3"
        fun verify(code: String) = SysVLuaStringPush.analyze(machineCode(code), 0x1000, 0x1100, 0x1200, 64)
        verify(valid)
        for (prefix in listOf("48 89 f7", "48 89 fe", "48 89 ca", "89 d2", "89 f6")) {
            val length = prefix.split(' ').size
            val displacement = 0xf7 - length
            assertFails { verify("53 48 89 fb $prefix e8 ${displacement.toString(16)} 00 00 00 5b c3") }
        }
        assertFails { verify("53 85 c0 74 05 e8 f6 00 00 00 5b c3") }
        assertFails { verify("90 48 89 fb e8 f7 00 00 00 90 c3") }
        assertFails { verify("53 48 89 fb e8 f7 00 00 00 eb 7f 5b c3") }
    }
}
