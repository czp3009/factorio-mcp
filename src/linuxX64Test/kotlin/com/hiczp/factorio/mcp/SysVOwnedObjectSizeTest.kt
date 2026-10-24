@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlinx.cinterop.toKString
import platform.posix.getenv

class SysVOwnedObjectSizeTest {
    @Test
    fun inlinedOwnerSizeUsesTheOwnedPointerAndRestoredDeletionTail() {
        val code =
            "53 48 8b 5f 18 48 c7 47 18 00 00 00 00 48 85 db 74 0e " +
                "be 80 01 00 00 48 89 df 5b e9 e0 00 00 00 5b c3"
        fun analyze(value: String) =
            SysVOwnedObjectSize.analyzeInlined(machineCode(value), 0x1000, 0x1100)
        assertEquals(OwnedObjectSize(24, 384), analyze(code))
        for (changed in
            listOf(
                code.replace("48 89 df", "48 89 c7"),
                code.replace("74 0e", "75 0e"),
                code.replace("be 80 01 00 00", "be 00 00 00 00"),
                code.replace("5b e9", "5d e9"),
                code.replace("48 85 db", "48 85 ff"),
            )) assertFails { analyze(changed) }
    }

    @Test
    fun establishesCompleteObjectSizeAndPointerLocationForBothOwnerPaths() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/allocation_fixture_$padding").use {
            file ->
            val image = ElfImage(file.view)
            val proof =
                SysVOwnedObjectSize.resolve(
                    image,
                    "fixture_destroy_owner",
                    "_ZN6ObjectD2Ev",
                    "fixture_delete",
                )
            fun constant(name: String): Long =
                image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            assertEquals(constant("fixture_object_size"), proof.size)
            assertEquals(constant("fixture_owner_pointer"), proof.pointer)
            val symbol = image.symbol("fixture_destroy_owner")
            val bytes = image.functionBytes(symbol, 256)
            val table = 0x1000L
            val address = symbol.address - 10
            val displacement = table - address - 7
            val prefix =
                byteArrayOf(0x48, 0x8d.toByte(), 0x05) +
                    ByteArray(4) { (displacement ushr (it * 8)).toByte() } +
                    byteArrayOf(0x48, 0x89.toByte(), 0x07)
            val polymorphic = BinaryView(prefix + bytes.bytes(0, bytes.size.toInt()))
            assertEquals(
                proof,
                SysVOwnedObjectSize.analyze(
                    polymorphic,
                    address,
                    image.symbol("_ZN6ObjectD2Ev").address,
                    image.symbol("fixture_delete").address,
                    table,
                ),
            )
            for (unverified in listOf(null, table + 8)) assertFails {
                SysVOwnedObjectSize.analyze(
                    polymorphic,
                    address,
                    image.symbol("_ZN6ObjectD2Ev").address,
                    image.symbol("fixture_delete").address,
                    unverified,
                )
            }
            assertFails {
                SysVOwnedObjectSize.analyze(
                    bytes,
                    symbol.address,
                    image.symbol("_ZN6ObjectD2Ev").address,
                    image.symbol("fixture_delete").address,
                    table,
                )
            }
            val branch =
                X64Instructions(bytes).all().first { it.operation == X64Instructions.Operation.JCC }
            val reversed = bytes.bytes(0, bytes.size.toInt())
            val opcode = branch.offset.toInt() + if (branch.size == 6) 1 else 0
            reversed[opcode] = (reversed[opcode].toInt() xor 1).toByte()
            assertFails {
                SysVOwnedObjectSize.analyze(
                    BinaryView(reversed),
                    symbol.address,
                    image.symbol("_ZN6ObjectD2Ev").address,
                    image.symbol("fixture_delete").address,
                )
            }
            assertFails {
                SysVOwnedObjectSize.analyze(
                    bytes,
                    symbol.address,
                    image.symbol("fixture_delete").address,
                    image.symbol("_ZN6ObjectD2Ev").address,
                )
            }
            assertFails {
                SysVOwnedObjectSize.analyze(
                    bytes.slice(0, branch.offset),
                    symbol.address,
                    image.symbol("_ZN6ObjectD2Ev").address,
                    image.symbol("fixture_delete").address,
                )
            }
        }
    }
}
