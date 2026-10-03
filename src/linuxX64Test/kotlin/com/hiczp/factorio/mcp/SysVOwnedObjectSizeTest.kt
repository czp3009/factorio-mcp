@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SysVOwnedObjectSizeTest {
    @Test
    fun establishesCompleteObjectSizeAndPointerLocationForBothOwnerPaths() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/allocation_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            val proof = SysVOwnedObjectSize.resolve(image, "fixture_destroy_owner", "_ZN6ObjectD2Ev", "fixture_delete")
            fun constant(name: String): Long =
                image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            assertEquals(constant("fixture_object_size"), proof.size)
            assertEquals(constant("fixture_owner_pointer"), proof.pointer)
            val symbol = image.symbol("fixture_destroy_owner")
            val bytes = image.functionBytes(symbol, 256)
            val table = 0x1000L
            val address = symbol.address - 10
            val displacement = table - address - 7
            val prefix = byteArrayOf(0x48, 0x8d.toByte(), 0x05) +
                    ByteArray(4) { (displacement ushr (it * 8)).toByte() } +
                    byteArrayOf(0x48, 0x89.toByte(), 0x07)
            val polymorphic = BinaryView(prefix + bytes.bytes(0, bytes.size.toInt()))
            assertEquals(proof, SysVOwnedObjectSize.analyze(polymorphic, address,
                image.symbol("_ZN6ObjectD2Ev").address, image.symbol("fixture_delete").address, table))
            for (unverified in listOf(null, table + 8)) assertFails {
                SysVOwnedObjectSize.analyze(polymorphic, address,
                    image.symbol("_ZN6ObjectD2Ev").address, image.symbol("fixture_delete").address, unverified)
            }
            assertFails {
                SysVOwnedObjectSize.analyze(bytes, symbol.address,
                    image.symbol("_ZN6ObjectD2Ev").address, image.symbol("fixture_delete").address, table)
            }
            val branch = X64Instructions(bytes).all().first { it.operation == X64Instructions.Operation.JCC }
            val reversed = bytes.bytes(0, bytes.size.toInt())
            val opcode = branch.offset.toInt() + if (branch.size == 6) 1 else 0
            reversed[opcode] = (reversed[opcode].toInt() xor 1).toByte()
            assertFails {
                SysVOwnedObjectSize.analyze(
                    BinaryView(reversed), symbol.address,
                    image.symbol("_ZN6ObjectD2Ev").address, image.symbol("fixture_delete").address
                )
            }
            assertFails {
                SysVOwnedObjectSize.analyze(
                    bytes, symbol.address,
                    image.symbol("fixture_delete").address, image.symbol("_ZN6ObjectD2Ev").address
                )
            }
            assertFails {
                SysVOwnedObjectSize.analyze(
                    bytes.slice(0, branch.offset), symbol.address,
                    image.symbol("_ZN6ObjectD2Ev").address, image.symbol("fixture_delete").address
                )
            }
        }
    }
}
