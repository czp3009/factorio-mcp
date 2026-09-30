@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFalse

class ElfEvidenceTest {
    @Test
    fun comparesCodeReadonlyBytesRelocatedPointersAndUnrelocatedScalars() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/byte_table_fixture_1").use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("fixture_byte_lookup")
            val table = image.symbol("fixture_byte_values").address
            // Synthetic pointer classification overlays fixture bytes to exercise relocation/range overlap.
            val evidence = ElfEvidence(
                listOf(function), listOf(ElfImage.ReadonlyRange(table, 24)),
                mapOf(table to function.address, table + 8 to 0L),
                mapOf(table + 16 to image.virtualBytes(table + 16, 8).unsigned(0, 8))
            )
            for (bias in listOf(0L, 0x100000L)) {
                fun read(address: Long, size: Int, corrupt: Long? = null): ByteArray {
                    val original = address - bias
                    val bytes = image.virtualBytes(original, size.toLong()).bytes(0, size)
                    for ((location, target) in evidence.pointers) for (index in 0..7) {
                        val offset = location + index - original
                        if (offset in 0 until size.toLong())
                            bytes[offset.toInt()] = ((if (target == 0L) 0 else target + bias) ushr (index * 8)).toByte()
                    }
                    if (corrupt != null && corrupt - original in 0 until size.toLong()) {
                        val index = (corrupt - original).toInt()
                        bytes[index] = (bytes[index].toInt() xor 1).toByte()
                    }
                    return bytes
                }
                evidence.verify(image, bias) { address, size -> read(address, size) }
                for (corrupt in listOf(function.address, table, table + 8, table + 16, table + 23))
                    assertFails { evidence.verify(image, bias) { address, size -> read(address, size, corrupt) } }
                assertFails {
                    evidence.verify(image, bias) { address, size ->
                        read(address, size).dropLast(1).toByteArray()
                    }
                }
            }
            for (bias in listOf(-8L, 1L, Long.MAX_VALUE - 7)) {
                var read = false
                assertFails {
                    evidence.verify(image, bias) { _, size ->
                        read = true
                        ByteArray(size)
                    }
                }
                assertFalse(read)
            }
        }
    }
}
