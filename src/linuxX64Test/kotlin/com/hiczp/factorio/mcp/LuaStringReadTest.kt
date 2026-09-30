@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LuaStringReadTest {
    @Test
    fun verifiesCompilerGeneratedIndexSizeAndReturnArguments() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/lua_string_read_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String): Long =
                image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

            val entry = image.symbol("fixture_tolstring")
            val code = image.functionBytes(entry, 4096)
            val index = image.symbol("fixture_index").address
            val result = SysVLuaStringRead.analyze(code, entry.address, index, constant("fixture_value_size"))
            assertEquals(constant("fixture_tag_offset"), result.tag)
            assertEquals(15L, result.mask)
            assertEquals(4L, result.type)
            assertEquals(constant("fixture_pointer_offset"), result.pointer)
            assertEquals(constant("fixture_length_offset"), result.length)
            assertEquals(constant("fixture_data_offset"), result.data)
            assertFails { SysVLuaStringRead.analyze(code, entry.address, index + 1, constant("fixture_value_size")) }
            assertFails { SysVLuaStringRead.analyze(code, entry.address, index, 8) }
        }
    }

    @Test
    fun rejectsChangedPointerArgumentsWidthsAndRestoration() {
        val code = "53 48 89 d3 e8 f7 00 00 00 8b 48 08 83 e1 0f 83 f9 04 75 19 " +
                "48 85 db 74 0a 48 8b 08 48 8b 49 18 48 89 0b 48 8b 00 48 83 c0 20 5b c3 90 c3"

        fun analyze(text: String) = SysVLuaStringRead.analyze(machineCode(text), 0x1000, 0x1100, 16)
        assertEquals(LuaStringResult(8, 4, 15, 4, 0, 24, 32), analyze(code))
        for (changed in listOf(
            code.replace("48 89 d3", "48 89 f3"),
            code.replace("48 89 d3", "48 89 cb"),
            code.replace("48 89 d3", "48 89 d7"),
            code.replace("48 89 d3", "48 89 d6"),
            code.replace("8b 48 08", "8b 48 18"),
            code.replace("48 89 0b", "89 0b 90"),
            code.replace("48 8b 49 18", "48 8b 49 20"),
            code.replace("48 8b 00", "48 8b 40 08"),
            code.replace("5b c3", "5d c3"),
            code.replace("74 0a", "74 7f"),
        )) assertFails { analyze(changed) }
    }
}
