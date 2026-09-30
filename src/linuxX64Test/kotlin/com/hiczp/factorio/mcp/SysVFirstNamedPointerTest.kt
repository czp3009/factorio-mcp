@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SysVFirstNamedPointerTest {
    @Test
    fun derivesSelectionFromCompilerGeneratedGuardsAndByteComparisons() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/named_pointer_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun value(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
            val function = image.symbol("fixture_select")
            val bytes = image.functionBytes(function, 8192).bytes(0, function.size.toInt())
            fun analyze(
                code: ByteArray = bytes, size: Long = value("fixture_element_size"),
                data: Long = value("fixture_text_data")
            ) = SysVFirstNamedPointer.resolve(
                BinaryView(code), function.address,
                value("fixture_owner_size"), size, data, value("fixture_text_length"),
                ArgumentScalar(6, value("fixture_text_length"), 8, 0)
            )

            val selected = analyze()
            val expected = image.symbol("fixture_expected")
                .let { image.virtualBytes(it.address, it.size).bytes(0, (it.size - 1).toInt()) }
            assertEquals(
                FirstNamedPointer(
                    value("fixture_begin"), value("fixture_end"), value("fixture_name"),
                    expected.toList()
                ), selected
            )
            assertFails { analyze(size = value("fixture_name") + value("fixture_text_length") + 7) }
            assertFails { analyze(data = value("fixture_text_data") + 1) }
            val instructions = X64Instructions(BinaryView(bytes)).all(2048)
            for (condition in listOf(4, 5)) {
                val branch = instructions.first { it.operation == Operation.JCC && it.condition == condition }
                val mutated = bytes.copyOf()
                val opcode = branch.offset.toInt() + if (branch.size == 6) 1 else 0
                mutated[opcode] = (mutated[opcode].toInt() xor 1).toByte()
                assertFails { analyze(mutated) }
            }
            // Removing one comparison from the OR must not authorize an unchecked character.
            val combine = instructions.first { it.operation == Operation.OR }
            val unchecked = bytes.copyOf()
            unchecked[combine.offset.toInt()] = 0x89.toByte() // MOV instead of OR, with the same ModRM.
            assertFails { analyze(unchecked) }
            val nameGuard = instructions.first { it.operation == Operation.JCC && it.condition == 5 }
            val absent = instructions.single { it.offset == (nameGuard.destination as Immediate).value }
            val uncleared = bytes.copyOf()
            val clearOpcode =
                absent.offset.toInt() + if (uncleared[absent.offset.toInt()].toInt() and 0xf0 == 0x40) 1 else 0
            uncleared[clearOpcode] = 0x09 // OR instead of clearing XOR.
            assertFails { analyze(uncleared) }
        }
    }
}
