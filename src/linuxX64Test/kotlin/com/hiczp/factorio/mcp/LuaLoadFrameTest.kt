@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LuaLoadFrameTest {
    @Test
    fun crossChecksCompilerGeneratedReaderAndLoaderRecords() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/lua_reader_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String): Long =
                image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

            val parser = image.symbol("fixture_parser")
            val reader = SysVLuaReader.analyze(image.functionBytes(parser, 8192))
            val entry = image.symbol("fixture_load")
            val code = image.functionBytes(entry, 4096)
            val protected = image.symbol("fixture_protected").address
            val stack = LuaStackLayout(constant("fixture_top_offset"), 0, 0, 16)
            val size = constant("fixture_state_size")
            val result = SysVLuaLoadFrame.analyze(code, entry.address, parser.address, protected, reader, stack, size)
            assertEquals(
                LuaLoadFrame(
                    reader,
                    constant("fixture_name_offset"),
                    constant("fixture_mode_offset"),
                    constant("fixture_base_offset"),
                    constant("fixture_error_offset"),
                    constant("fixture_counter_offset")
                ), result
            )
            assertFails {
                SysVLuaLoadFrame.analyze(
                    code,
                    entry.address,
                    parser.address + 1,
                    protected,
                    reader,
                    stack,
                    size
                )
            }
            assertFails {
                SysVLuaLoadFrame.analyze(
                    code,
                    entry.address,
                    parser.address,
                    protected + 1,
                    reader,
                    stack,
                    size
                )
            }
            assertFails {
                SysVLuaLoadFrame.analyze(
                    code, entry.address, parser.address, protected,
                    reader.copy(state = reader.userdata, userdata = reader.state), stack, size
                )
            }
            assertFails { SysVLuaLoadFrame.analyze(code, entry.address, parser.address, protected, reader, stack, 8) }
            val instructions = X64Instructions(code).all()
            fun corrupted(offset: Long, bits: Int): BinaryView {
                val bytes = ByteArray(code.size.toInt()) { code.unsigned(it.toLong(), 1).toByte() }
                bytes[offset.toInt()] = (bytes[offset.toInt()].toInt() xor bits).toByte()
                return BinaryView(bytes)
            }
            // A nonzero/unknown initial count cannot authorize the parser's first-refill path.
            val zero = instructions.first { it.operation == X64Instructions.Operation.VECTOR_XOR }
            assertFails {
                SysVLuaLoadFrame.analyze(
                    corrupted(zero.offset + zero.size - 1, 1), entry.address,
                    parser.address, protected, reader, stack, size
                )
            }
            // Removing the operand-width prefix would increment a different-width state field.
            val counter = instructions.first { it.operation == X64Instructions.Operation.INC }
            assertEquals(0x66L, code.unsigned(counter.offset, 1))
            assertFails {
                SysVLuaLoadFrame.analyze(
                    corrupted(counter.offset, 0x66 xor 0x90), entry.address,
                    parser.address, protected, reader, stack, size
                )
            }
        }
    }
}
