@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class ControlGuiFlagTest {
    @Test
    fun resolvesLinkedOwnerFlagsWithVariedPlacementAndMasks() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val fields = listOf(1, 23).map { padding ->
            MappedBinary("$directory/control_gui_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String, width: Int = 8) =
                    image.symbol(name).let { image.virtualBytes(it.address, width.toLong()).unsigned(0, width) }

                val extent = constant("fixture_control_size")
                val shift = constant("fixture_gui_shift", 4).toInt()
                val expected = NativeAccessor(constant("fixture_gui_field"), 1, 1uL shl shift, shift)
                fun resolve(size: Long) = ControlGuiFlag.resolve(
                    image, size, "_ZNK7Control6activeEv",
                    "_ZNK5Value6activeEb", "fixture_in_gui"
                )
                assertEquals(expected, resolve(extent))
                assertFails { resolve(expected.offset) }
                expected
            }
        }
        assertNotEquals(fields[0].offset, fields[1].offset)
        assertNotEquals(fields[0].mask, fields[1].mask)
    }

    @Test
    fun rejectsForeignOwnersModifiedBitsAndDifferentValueArguments() {
        val code = "0f b6 73 18 83 e6 01 e8 f4 0f 00 00 0f b6 73 18 83 e6 01 e8 e8 0f 00 00 c3"
        fun analyze(text: String) = ControlGuiFlag.analyze(machineCode(text), 0x10000, 0x11000, 3, 6, 64)
        assertEquals(NativeAccessor(24, 1, 1uL, 0), analyze(code))
        for (changed in listOf(
            code.replace("0f b6 73 18", "0f b6 77 18"),
            code.replace("0f b6 73 18", "0f b7 73 18"),
            code.replace("0f b6 73 18", "0f b6 73 40"),
            code.replaceFirst("0f b6 73 18", "0f b6 73 19"),
            code.replace("83 e6 01", "83 e6 03"),
            code.replace("83 e6 01", "83 f6 01"),
            code.replace("e8 e8 0f", "e8 e8 1f"),
        )) assertFails { analyze(changed) }
    }

    @Test
    fun requiresOriginalNonzeroArgumentToGuardTheNamedCall() {
        val code = "55 48 89 e5 40 84 f6 74 06 e8 f2 0f 00 00 90 c3"
        fun analyze(text: String) = CallGateArgument.analyze(machineCode(text), 0x10000, 0x11000)
        assertEquals(6, analyze(code))
        for (changed in listOf(
            code.replace("74 06", "75 06"),
            code.replace("74 06", "74 01"),
            code.replace("40 84 f6", "40 84 ff"),
            code.replace("e8 f2 0f", "e8 f2 1f"),
            code.replace("40 84 f6", "40 84 f2"),
            "85 c0 74 09 " + code.replace("e8 f2", "e8 ee"),
            "85 c0 74 07 " + code.replace("e8 f2", "e8 ee"),
        )) assertFails { analyze(changed) }
    }
}
