@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class ControlMouseKindTest {
    @Test
    fun resolvesVariedTypesAndFieldsFromNativeMasks() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val kinds = listOf(1, 23).map { padding ->
            MappedBinary("$directory/control_mouse_kind_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
                val extent = constant("fixture_size")
                val type = constant("fixture_type")
                val code = constant("fixture_code")
                val kind = constant("fixture_kind").toInt()
                val function = "_ZNK5Value9mouseMaskEv"
                assertEquals(kind, ControlMouseKind.resolve(image, extent, type, code, function))
                assertFails { ControlMouseKind.resolve(image, code + 3, type, code, function) }
                assertFails { ControlMouseKind.resolve(image, extent, type + 1, code, function) }
                assertFails { ControlMouseKind.resolve(image, extent, type, code + 1, function) }
                val flow = X64ControlFlow.resolve(image, image.symbol(function))
                fun changed(site: Long, change: (X64Instructions.Instruction) -> X64Instructions.Instruction) =
                    ControlMouseKind.analyze(X64ControlFlow(flow.instructions.map {
                        if (it.offset == site) change(it) else it
                    }), extent, type, code)
                for (branch in flow.instructions.filter { it.operation == Operation.JCC }) {
                    assertFails { changed(branch.offset) { it.copy(condition = checkNotNull(it.condition) xor 1) } }
                }
                val seed = flow.instructions.single {
                    it.operation == Operation.MOV &&
                            it.destination == Register(0, 4) && it.source == Immediate(1)
                }
                assertFails { changed(seed.offset) { it.copy(source = Immediate(2)) } }
                assertFails { changed(seed.offset) { it.copy(destination = Register(0, 1)) } }
                val shift = flow.instructions.single { it.operation == Operation.SHL }
                assertFails { changed(shift.offset) { it.copy(source = Register(2, 1)) } }
                assertFails { changed(shift.offset) { it.copy(operation = Operation.SHR) } }
                val bound = flow.instructions.single {
                    it.operation == Operation.CMP &&
                            (it.destination as? Register)?.width == 4
                }
                assertFails { changed(bound.offset) { it.copy(source = Immediate(32)) } }
                kind
            }
        }
        assertNotEquals(kinds[0], kinds[1])
    }

    @Test
    fun requiresOriginalCodeAndUnsignedBoundsThroughRegisterTypeChecks() {
        val bytes = "0f b6 17 80 fa 0d 75 12 8b 4f 04 83 f9 09 77 0a b8 01 00 00 00 d3 e0 c3 90 90 c3"
        fun analyze(code: String) =
            ControlMouseKind.analyze(X64ControlFlow(X64Instructions(machineCode(code)).all()), 16, 0, 4)
        assertEquals(13, analyze(bytes))
        for (changed in listOf(
            bytes.replace("0f b6 17", "0f b6 16"),
            bytes.replace("8b 4f 04", "8b 4e 04"),
            bytes.replace("8b 4f 04", "8b 4f 08"),
            bytes.replace("83 f9 09", "83 fa 09"),
            bytes.replace("77 0a", "7f 0a"),
            bytes.replace("77 0a", "77 00"),
            bytes.replace("75 12", "74 12"),
            bytes.replace("b8 01 00 00 00", "b0 01 90 90 90"),
            bytes.replace("b8 01 00 00 00", "89 d1 90 90 90"),
            "c6 07 0d $bytes",
            "c7 47 04 00 00 00 00 $bytes",
        )) assertFails { analyze(changed) }
    }
}
