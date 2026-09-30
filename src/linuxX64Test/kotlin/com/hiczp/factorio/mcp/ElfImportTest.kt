@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ElfImportTest {
    @Test
    fun checksResolverIndexAndGotRelocationForRewrittenPltEntries() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/debug_fixture_5").use { file ->
            val image = ElfImage(file.view)
            val wrapper = image.symbol("fixture_never_return")
            val call = X64Instructions(image.functionBytes(wrapper, 256)).all().first {
                it.operation in setOf(X64Instructions.Operation.CALL, X64Instructions.Operation.JMP)
            }
            val address = wrapper.address + (call.destination as X64Instructions.Immediate).value
            assertEquals("abort", image.importedFunction(address))
            val jump = X64Instructions(image.virtualBytes(address, 16, true)).decode(0)
            val got = address + jump.size + (jump.destination as X64Instructions.Memory).displacement
            val segment =
                image.segments.single { it.type == 1L && address >= it.address && address - it.address < it.fileSize }
            val start = (segment.offset + address - segment.address).toInt()
            val relocation = image.sections.filter { it.type == 4L }.flatMap { section ->
                (0 until section.size / 24).filter { index ->
                    file.view.unsigned(
                        section.offset + index * 24,
                        8
                    ) == got
                }
                    .map { index -> section to index }
            }.single()
            val bytes = file.view.bytes(0, file.view.size.toInt())
            val prefix = machineCode("41 bb 00 00 00 00 ff 25 00 00 00 00").bytes(0, 12)
            prefix.copyInto(bytes, start)
            fun put32(offset: Int, value: Long) {
                for (index in 0..3) bytes[offset + index] = (value ushr (index * 8)).toByte()
            }
            put32(start + 2, relocation.second)
            put32(start + 8, got - address - prefix.size)
            assertEquals("abort", ElfImage(BinaryView(bytes)).importedFunction(address))
            put32(start + 2, relocation.second + 1)
            assertFails { ElfImage(BinaryView(bytes)).importedFunction(address) }
            put32(start + 2, relocation.second)
            bytes[(relocation.first.offset + relocation.second * 24 + 8).toInt()] = 8
            assertFails { ElfImage(BinaryView(bytes)).importedFunction(address) }
        }
    }
}
