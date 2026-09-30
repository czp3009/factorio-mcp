@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class LinkedReceiverPrefixTest {
    @Test
    fun matchesNativeForwardingWithDifferentMemberPlacement() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val members = listOf(1, 23).map { padding ->
            MappedBinary("$directory/linked_receiver_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String): Long {
                    val symbol = image.symbol(name)
                    return image.virtualBytes(symbol.address, symbol.size).unsigned(0, 8)
                }

                val extent = constant("fixture_control_extent")
                val expected = constant("fixture_control_link")
                for (function in listOf("_ZNK7Control6directEv", "_ZNK7Control7guardedEv")) {
                    assertEquals(expected, LinkedReceiverPrefix.resolve(image, function, extent).member)
                    assertFails { LinkedReceiverPrefix.resolve(image, function, expected + 7) }
                }
                expected
            }
        }
        assertNotEquals(members[0], members[1])
    }

    @Test
    fun requiresNullDrivenForwardingOfTheSameBoundedMember() {
        // Synthetic fields, unrelated to game layouts. RBX retains each nonnull RAX cursor.
        val direct = "55 48 89 e5 53 48 89 f8 48 89 c3 48 8b 40 18 48 85 c0 75 f4 90"
        val guarded = "55 48 89 e5 53 48 89 fb 48 8b 47 18 48 85 c0 74 0c " +
                "48 89 c3 48 8b 40 18 48 85 c0 75 f4 90"
        for (code in listOf(direct, guarded)) {
            val proof = LinkedReceiverPrefix.analyze(machineCode(code), 0x10000, 64)
            assertEquals(24L, proof.member)
            assertEquals(3, proof.receiver)
            for (changed in listOf(
                code.replace("75 f4", "74 f4"),
                code.replace("75 f4", "75 f7"),
                code.replace("48 85 c0", "48 85 db"),
                code.replace("48 8b 40 18", "48 8b 48 18"),
                code.replace("48 8b 40 18", "48 8b 41 18"),
                code.replace("48 8b 40 18", "48 8b 40 40"),
                code.replace("48 89 c3", "48 89 d3"),
            )) assertFails { LinkedReceiverPrefix.analyze(machineCode(changed), 0x10000, 64) }
        }
        for (changed in listOf(
            guarded.replace("74 0c", "75 0c"), guarded.replace("74 0c", "74 03"),
            guarded.replace("48 8b 47 18", "48 8b 47 20")
        )) {
            assertFails { LinkedReceiverPrefix.analyze(machineCode(changed), 0x10000, 64) }
        }
    }
}
