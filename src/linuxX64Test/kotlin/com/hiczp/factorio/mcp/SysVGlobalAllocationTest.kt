@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SysVGlobalAllocationTest {
    @Test
    fun resolvesNonvirtualObjectBoundsFromCompilerGeneratedPublication() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/allocation_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            val proof = SysVGlobalAllocation.resolve(
                image, "fixture_publish", "_ZN6ObjectC2EPK6Source",
                "fixture_global", "fixture_allocate"
            )
            val sizeSymbol = image.symbol("fixture_object_size")
            assertEquals(image.virtualBytes(sizeSymbol.address, 8).unsigned(0, 8), proof.size)
            assertTrue(proof.prefixSize in 1..512)
            assertFails {
                SysVGlobalAllocation.resolve(
                    image, "fixture_publish", "_ZN6ObjectC2EPK6Source",
                    "fixture_flag", "fixture_allocate"
                )
            }
        }
    }

    @Test
    fun refusesWrongReceiverPublicationAndCallBoundaries() {
        // Synthetic factory: allocate 96 bytes, construct, publish the saved pointer to address 1024.
        val bytes = byteArrayOf(
            0x53, 0xbf.toByte(), 96, 0, 0, 0,
            0xe8.toByte(), 117, 0, 0, 0,
            0x48, 0x89.toByte(), 0xc3.toByte(), 0x48, 0x89.toByte(), 0xc7.toByte(),
            0xe8.toByte(), 106, 0, 0, 0,
            0x48, 0x89.toByte(), 0x1d, 0xe3.toByte(), 3, 0, 0, 0x5b, 0xc3.toByte()
        )

        fun analyze(value: ByteArray = bytes) = SysVGlobalAllocation.analyze(BinaryView(value), 0, 128, 128 + 0L, 1024)
        // Distinct named functions are required, even if an optimizer aliases symbols.
        assertFails { analyze() }
        val valid = bytes.copyOf().also { it[18] = 122 }
        fun proof(value: ByteArray = valid) = SysVGlobalAllocation.analyze(BinaryView(value), 0, 128, 144, 1024)
        assertEquals(GlobalAllocation(96, 29), proof())
        fun changed(index: Int, value: Int) = valid.copyOf().also { it[index] = value.toByte() }
        assertFails { proof(changed(16, 0xd7)) }
        assertFails { proof(changed(24, 0x05)) } // Publish the call-clobbered RAX.
        assertFails { proof(changed(18, 121)) } // Constructor target differs by one byte.
        assertFails { proof(changed(1, 0xbe)) } // Size is passed in RSI instead of RDI.
        assertFails { proof(valid.copyOf(28)) }
        assertFails { proof(changed(6, 0xe9)) } // Branching to allocation is not a call.
    }
}
