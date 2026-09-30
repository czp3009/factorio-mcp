@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class SysVPointerGateTest {
    @Test
    fun derivesCompilerGuardsWithoutExecutingTheContinuation() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val members = mutableListOf<Long>()
        for (padding in listOf(1, 23)) MappedBinary("$directory/hover_gate_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun scalar(name: String): Long {
                val symbol = image.symbol(name)
                return image.virtualBytes(symbol.address, symbol.size).unsigned(0, 8)
            }

            val size = scalar("fixture_gui_size")
            val proof = SysVPointerGate.resolve(image, "fixture_hover", size)
            assertEquals(scalar("fixture_gate_offset"), proof.member)
            assertFails { SysVPointerGate.resolve(image, "fixture_hover", proof.member + 7) }
            members += proof.member
        }
        assertNotEquals(members[0], members[1])
    }

    @Test
    fun rejectsReversedGuardsSideEffectsAndUnbalancedReturns() {
        val code = "55 48 89 e5 53 48 83 ec 08 48 83 7f 20 00 74 07 48 83 c4 08 5b 5d c3 e8 00 00 00 00 c3"
        assertEquals(32, SysVPointerGate.analyze(machineCode(code), 64).member)
        for (changed in listOf(
            code.replace("74 07", "75 07"),
            code.replace("48 83 c4 08", "48 83 c4 10"),
            code.replace("48 83 7f 20", "48 83 7e 20"),
            "48 89 57 10 $code",
            "e8 00 00 00 00 $code",
        )) assertFails(changed) { SysVPointerGate.analyze(machineCode(changed), 64) }
    }
}
