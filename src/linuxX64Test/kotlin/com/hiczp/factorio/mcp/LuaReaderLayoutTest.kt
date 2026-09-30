@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LuaReaderLayoutTest {
    @Test
    fun derivesPaddedStreamMembersFromCompilerGeneratedRefill() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/lua_reader_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String): Long =
                image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            assertEquals(
                LuaReaderLayout(
                    constant("fixture_stream_offset"),
                    constant("fixture_count_offset"),
                    constant("fixture_callback_offset"),
                    constant("fixture_state_offset"),
                    constant("fixture_userdata_offset")
                ),
                SysVLuaReader.analyze(image.functionBytes(image.symbol("fixture_parser"), 8192))
            )
        }
    }

    @Test
    fun rejectsWrongStreamArgumentsCountGuardsAndFrameOutputs() {
        val code = "53 48 83 ec 10 48 8b 1e 48 83 2b 01 73 16 48 8b 73 18 48 8b 7b 20 " +
                "48 8d 14 24 ff 53 10 48 83 c4 10 5b c3 90 c3"

        fun analyze(text: String) = SysVLuaReader.analyze(machineCode(text))
        assertEquals(LuaReaderLayout(0, 0, 16, 32, 24), analyze(code))
        for (changed in listOf(
            code.replace("48 8b 1e", "48 8b 1f"),
            code.replace("48 83 2b 01", "48 83 2b 02"),
            code.replace("73 16", "74 16"),
            code.replace("73 16", "73 01"),
            code.replace("48 8b 7b 20", "48 8b 7b 18"),
            code.replace("48 8b 73 18", "48 8b 73 00"),
            code.replace("48 8d 14 24", "48 8d 54 24 10"),
            code.replace("ff 53 10", "ff 53 18"),
            code.replace("48 8b 7b 20", "8b 7b 20 90"),
        )) assertFails { analyze(changed) }
    }
}
