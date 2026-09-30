@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertFails

class LuaParserArgumentsTest {
    @Test
    fun crossChecksCompilerGeneratedModeAndNameConsumption() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/lua_reader_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String): Long =
                image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

            val parser = image.symbol("fixture_parser")
            val code = image.functionBytes(parser, 8192)
            val loader = image.symbol("fixture_load")
            val layout = SysVLuaLoadFrame.analyze(
                image.functionBytes(loader, 4096), loader.address, parser.address,
                image.symbol("fixture_protected").address, SysVLuaReader.analyze(code),
                LuaStackLayout(constant("fixture_top_offset"), 0, 0, 16), constant("fixture_state_size")
            )

            fun verify(value: LuaLoadFrame) = SysVLuaParserArguments.analyze(code, parser.address, value) {
                image.importedFunction(it) == "strchr"
            }
            verify(layout)
            assertFails { verify(layout.copy(name = layout.mode, mode = layout.name)) }
            assertFails { verify(layout.copy(name = layout.name + 1)) }
            assertFails { SysVLuaParserArguments.analyze(code, parser.address, layout) { false } }
        }
    }
}
