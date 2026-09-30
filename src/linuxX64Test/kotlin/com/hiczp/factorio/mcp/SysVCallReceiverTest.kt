@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class SysVCallReceiverTest {
    @Test
    fun resolvesCompilerGeneratedReceiverLoadsAcrossDifferentLayouts() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val resolved = mutableListOf<Long>()
        for (padding in listOf(1, 23)) MappedBinary("$directory/accessor_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun compilerValue(name: String): Long {
                val symbol = image.symbol(name)
                return image.virtualBytes(symbol.address, symbol.size).unsigned(0, 8)
            }

            val offset = SysVCallReceiver.resolve(
                image,
                "fixture_call",
                "fixture_member_call",
                compilerValue("fixture_accessor_size")
            )
            assertEquals(compilerValue("fixture_pointer_offset"), offset)
            resolved += offset
        }
        assertNotEquals(resolved[0], resolved[1])
    }

    @Test
    fun tracksSavedReceiverAndStackSpillsBeforeDirectDispatch() {
        val code = machineCode("55 48 89 e5 48 83 ec 10 89 75 f8 48 89 f9 48 8b 79 18 e8 00 00 00 00")
        assertEquals(24, SysVCallReceiver.analyze(code, 0x1000, 0x1000 + code.size, 32))
        assertFails { SysVCallReceiver.analyze(code, 0x1000, 0x1000 + code.size + 1, 32) }
        assertFails { SysVCallReceiver.analyze(code, 0x1000, 0x1000 + code.size, 31) }
    }

    @Test
    fun rejectsMutationUnknownReceiversIndirectCallsAndBranches() {
        val rejected = listOf(
            "55 48 89 e5 48 8b 7e 18 e8 00 00 00 00",
            "55 48 89 e5 48 8b 7f 18 48 89 77 08 e8 00 00 00 00",
            "55 48 89 e5 48 8b 7f 18 48 8b 7f 08 e8 00 00 00 00",
            "55 48 89 e5 74 00 48 8b 7f 18 e8 00 00 00 00",
            "55 48 89 e5 48 8b 7f 18 ff d0",
            "48 8b 7f 18 e8 00 00 00 00", // Missing System V stack alignment.
            "55 48 89 e5 89 75 08 48 8b 7f 18 e8 00 00 00 00", // Store into the caller's frame.
        )
        for (code in rejected) {
            val bytes = machineCode(code)
            assertFails(code) { SysVCallReceiver.analyze(bytes, 0x1000, 0x1000 + bytes.size, 32) }
        }
    }
}
