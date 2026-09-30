@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LuaCallRecordTest {
    @Test
    fun derivesCompilerGeneratedProtectedCallRecords() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/lua_call_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String): Long =
                image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

            val callback = image.symbol("fixture_call")
            val dispatch = image.symbol("fixture_dispatch")
            assertEquals(
                LuaCallRecord(constant("fixture_function_offset"), constant("fixture_results_offset")),
                SysVLuaCallRecord.analyze(image.functionBytes(callback, 512), callback.address, dispatch.address)
            )
        }
    }

    @Test
    fun rejectsWrongUserdataArgumentWidthsYieldAndFrameRestoration() {
        val tail = "55 48 89 e5 48 8b 06 8b 56 08 48 89 c6 31 c9 5d e9 eb 00 00 00"
        fun analyze(code: String) = SysVLuaCallRecord.analyze(machineCode(code), 0x1000, 0x1100)
        assertEquals(LuaCallRecord(0, 8), analyze(tail))
        assertEquals(LuaCallRecord(0, 8), analyze("55 48 89 e5 48 8b 06 8b 56 08 48 89 c6 31 c9 e8 ec 00 00 00 5d c3"))
        for (code in listOf(
            tail.replace("48 8b 06", "48 8b 07"),
            tail.replace("8b 56 08", "8b 56 04"),
            tail.replace("8b 56 08", "8b 4e 08"),
            tail.replace("48 89 c6", "48 89 c7"),
            tail.replace("48 89 c6", "89 c6 90"),
            tail.replace("31 c9", "31 d2"),
            tail.replace("5d e9", "5b e9"),
            tail.replace("e9 eb", "e9 ea"),
            tail.replace("e9 eb 00 00 00", "c3 90 90 90 90"),
        )) assertFails { analyze(code) }
    }
}
