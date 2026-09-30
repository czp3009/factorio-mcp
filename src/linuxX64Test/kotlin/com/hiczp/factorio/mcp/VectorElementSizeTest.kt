@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class VectorElementSizeTest {
    @Test
    fun matchesIndependentlyCompiledNonvirtualElementSizes() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val sizes = listOf(1, 23).map { padding ->
            MappedBinary("$directory/vector_element_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                val size = VectorElementSize.resolve(image, "fixture_destroy_elements", "_ZN14FixtureElementD2Ev")
                val expected = image.symbol("fixture_element_size")
                assertEquals(image.virtualBytes(expected.address, expected.size).unsigned(0, 8), size)
                size
            }
        }
        assertNotEquals(sizes[0], sizes[1])
    }

    @Test
    fun rejectsBrokenTypedReceiverAndLoopEvidence() {
        // Synthetic container fields and element extent, independent of any game layout.
        val code = "55 48 89 e5 41 56 53 48 8b 5f 10 4c 8b 77 18 4c 39 f3 74 11 " +
                "48 89 df e8 e4 00 00 00 48 83 c3 38 4c 39 f3 75 ef 5b 41 5e 5d c3"
        assertEquals(56L, VectorElementSize.analyze(machineCode(code), 0x10000, 0x10100))
        assertEquals(
            56L, VectorElementSize.analyze(
                machineCode(code.replace("e8 e4 00 00 00", "e8 e4 ff ff ff")), 0x10000, 0x10000
            )
        )
        assertFails {
            VectorElementSize.analyze(machineCode(code.replace("e8 e4 00 00 00", "e8 e5 ff ff ff")), 0x10000, 0x10000)
        }
        assertEquals(
            56L, VectorElementSize.analyze(
                machineCode(code.replace("48 83 c3 38", "48 83 eb c8")), 0x10000, 0x10100
            )
        )
        for (changed in listOf(
            code.replace("48 83 c3 38", "48 83 eb 38"),
            code.replace("48 83 c3 38", "48 83 eb 00"),
            code.replace("48 89 df", "4c 89 f7"),
            code.replace("48 83 c3 38", "48 83 c3 00"),
            code.replace("48 83 c3 38", "48 83 c6 38"),
            code.replace("75 ef", "74 ef"),
            code.replace("75 ef", "75 e2"),
            code.replace("74 11", "75 11"),
            code.replace("74 11", "74 05"),
            code.replace("4c 8b 77 18", "4c 8b 77 10"),
            code.replace("e8 e4", "e8 e3"),
        )) assertFails(changed) { VectorElementSize.analyze(machineCode(changed), 0x10000, 0x10100) }
    }
}
