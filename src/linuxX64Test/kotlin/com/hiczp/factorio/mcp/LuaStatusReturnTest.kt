@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertFails

class LuaStatusReturnTest {
    @Test
    fun verifiesCompilerGeneratedLoaderResult() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/lua_reader_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            val entry = image.symbol("fixture_load")
            SysVLuaStatusReturn.analyze(
                image.functionBytes(entry, 4096),
                entry.address,
                image.symbol("fixture_protected").address
            )
        }
    }

    @Test
    fun rejectsChangedResultsAndCallerSavedCopiesAcrossCleanupCalls() {
        val code = "53 e8 fa 00 00 00 89 c3 e8 f3 01 00 00 89 d8 5b c3"
        fun verify(text: String) = SysVLuaStatusReturn.analyze(machineCode(text), 0x1000, 0x1100)
        verify(code)
        assertFails { verify(code.replace("89 d8", "89 f8")) }
        assertFails { verify(code.replace("89 c3", "89 c1").replace("89 d8", "89 c8")) }
        assertFails { verify(code.replace("89 d8", "b0 00")) }
        assertFails { verify(code.replace("89 d8", "31 c0")) }
        assertFails { verify(code.replace("89 c3", "89 04 24")) }
        assertFails { verify(code.replace("fa 00", "fb 00")) }
    }
}
