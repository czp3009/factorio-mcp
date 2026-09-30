@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class GuardedByteTableTest {
    @Test
    fun resolvesCompilerGeneratedTablesForEveryInputByte() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/byte_table_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("fixture_byte_lookup")
            val table = GuardedByteTable.resolve(image, function).single()
            val field = image.symbol("fixture_byte_offset").let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val values = image.symbol("fixture_byte_values")
            assertEquals(values.address, table.address)
            assertEquals(SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, field), 1), table.input)
            for (byte in 0..255) {
                val index = (byte - padding) and 255
                assertEquals(
                    if (index < 9) image.virtualBytes(values.address + index * 4, 4).unsigned(0, 4) else null,
                    table.value(byte)
                )
            }
            assertFails { table.value(-1) }
            assertFails { table.value(256) }
        }
    }

    @Test
    fun rejectsMissingBoundsWrongWidthsChangedIndicesAndBypassedGuards() {
        val load = "0f b6 47 03"
        val arithmetic = "fe c8"
        val comparison = "3c 02"
        val guard = "77 0e"
        val extension = "0f b6 c0"
        val table = "48 8d 15 00 01 00 00"
        val lookup = "8b 04 82"
        val code = "$load $arithmetic $comparison $guard $extension $table $lookup c3 b8 ff ff ff ff c3"
        fun resolve(text: String, short: Boolean = false): GuardedByteTable.Proof {
            val flow = X64ControlFlow(X64Instructions(machineCode(text)).all())
            return GuardedByteTable.analyze(flow, 0) { _, size ->
                BinaryView(ByteArray(if (short) 1 else size.toInt()) { it.toByte() })
            }.single()
        }

        val result = resolve(code)
        assertEquals(listOf(1, 2, 3), result.indices.withIndex().filter { it.value != null }.map { it.index })
        assertFails { resolve(code, short = true) }
        assertFails { resolve(code.replace(guard, "7f 0e")) }
        assertFails { resolve(code.replace(extension, "0f b6 c1")) }
        assertFails { resolve(code.replace(comparison, "3c ff").replace(extension, "90 90 90")) }
        assertFails { resolve(code.replace(lookup, "8b 04 8a")) }
        assertFails { resolve(code.replace(load, "0f b7 47 03")) }
        assertFails { resolve(code.replace(load, "0f b6 40 03")) }
        assertFails { resolve(code.replace(arithmetic, "88 c8")) }
        assertFails { resolve("eb 0a $code") }
        assertFails { resolve(code.replace(guard, "77 03")) }
    }
}
