@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LuaProtectedCallTest {
    @Test
    fun verifiesCompilerGeneratedFiveAndSixArgumentCalls() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/lua_pcall_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String): Long =
                image.symbol("fixture_$name").let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

            val stack =
                LuaStackLayout(constant("top_offset"), constant("frame_offset"), constant("function_offset"), 16)
            val record = LuaCallRecord(constant("record_function"), constant("record_results"))
            for (arguments in listOf(5, 6)) {
                val entry = image.symbol("fixture_pcall$arguments")
                fun analyze(
                    layout: LuaStackLayout = stack,
                    fields: LuaCallRecord = record,
                    base: Long = constant("base_offset")
                ) =
                    SysVLuaProtectedCall.analyze(
                        image.functionBytes(entry, 4096), entry.address,
                        image.symbol("fixture_callback").address, image.symbol("fixture_protected").address,
                        image.symbol("fixture_abort").address, layout, base, fields, constant("state_size")
                    )
                // The specialized entry also gives a useful diagnostic if a compiler changes its supported code shape.
                for (nargs in listOf(2, 10)) assertEquals(
                    LuaProtectedCall(arguments, constant("status_offset"), constant("limit_offset")),
                    SysVLuaProtectedCall.specialize(
                        image.functionBytes(entry, 4096),
                        entry.address,
                        image.symbol("fixture_callback").address,
                        image.symbol("fixture_protected").address,
                        image.symbol("fixture_abort").address,
                        stack,
                        constant("base_offset"),
                        record,
                        constant("state_size"),
                        arguments,
                        nargs
                    )
                )
                assertEquals(
                    LuaProtectedCall(arguments, constant("status_offset"), constant("limit_offset")),
                    analyze()
                )
                assertFails { analyze(layout = stack.copy(top = stack.top + 1)) }
                assertFails { analyze(fields = record.copy(results = record.results + 4)) }
                assertFails { analyze(base = constant("base_offset") + 1) }
                val code = image.functionBytes(entry, 4096)
                fun rejectChange(offset: Long, replacement: Int) {
                    val changed = code.bytes(0, code.size.toInt())
                    changed[offset.toInt()] = replacement.toByte()
                    assertFails("Accepted mutation at $offset in fixture_pcall$arguments") {
                        SysVLuaProtectedCall.analyze(
                            BinaryView(changed),
                            entry.address,
                            image.symbol("fixture_callback").address,
                            image.symbol("fixture_protected").address,
                            image.symbol("fixture_abort").address,
                            stack,
                            constant("base_offset"),
                            record,
                            constant("state_size")
                        )
                    }
                }

                val instructions = X64Instructions(code).all()
                for (instruction in instructions) when (instruction.operation) {
                    Operation.JCC -> {
                        // Invert each guard, including error/continuation and the multret alternative.
                        val opcode = instruction.offset + if (code.unsigned(instruction.offset, 1) == 0x0fL) 1 else 0
                        rejectChange(opcode, code.unsigned(opcode, 1).toInt() xor 1)
                    }

                    Operation.SAR, Operation.SHL -> rejectChange(instruction.offset + instruction.size - 1, 3)
                    Operation.CALL -> {
                        val offset = instruction.offset + instruction.size - 4
                        rejectChange(offset, code.unsigned(offset, 1).toInt() xor 1)
                    }

                    else -> Unit
                }
            }
        }
    }
}
