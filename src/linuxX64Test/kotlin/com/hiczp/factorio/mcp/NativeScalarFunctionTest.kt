@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class NativeScalarFunctionTest {
    @Test
    fun evaluatesCompilerGeneratedKeySwitchAndRejectsForeignInputs() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        MappedBinary("$directory/switch_fixture").use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("fixture_key_conversion")
            assertEquals(1, X64JumpTables.resolve(image, function).size)
            val expected = listOf(-97, 419, 237, 813, 129, 731, 617, 523, 941, -97)
            expected.forEachIndexed { index, value ->
                assertEquals(value, NativeScalarFunction.evaluate(image, function, index + 10))
            }
            for (input in listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE))
                assertEquals(-97, NativeScalarFunction.evaluate(image, function, input))
            assertFails { NativeScalarFunction.evaluate(image, image.symbol("fixture_switch"), 11) }
        }
    }

    @Test
    fun tracksPrivateFrameAllocationWithoutDiscardingSavedRegisters() {
        fun evaluate(code: String): Int = NativeScalarFunction.evaluate(
            X64ControlFlow(X64Instructions(machineCode(code)).all()), 0x1000, emptyList(), 7
        )

        val valid = "55 48 89 e5 53 48 83 ec 28 89 f8 48 83 c4 28 5b 5d c3"
        assertEquals(7, evaluate(valid))
        for (invalid in listOf(
            valid.replace("ec 28", "ec 27"),
            valid.replace("c4 28", "c4 30"),
            valid.replace("c4 28", "c4 20"),
            valid.replace("48 83 ec 28", "48 81 ec 08 01 00 00"),
        )) assertFails { evaluate(invalid) }
    }

    @Test
    fun rejectsUnprovenArgumentsMutationsCallsLoopsAndDamagedFrames() {
        fun evaluate(code: String, input: Int = 3): Int = NativeScalarFunction.evaluate(
            X64ControlFlow(X64Instructions(machineCode(code)).all()), 0x1000, emptyList(), input
        )
        assertEquals(10, evaluate("55 48 89 e5 8d 47 07 5d c3"))
        assertEquals(-1, evaluate("89 f8 83 e8 04 c3"))
        for (code in listOf(
            "89 f0 c3", "89 07 89 f8 c3", "8b 07 c3", "e8 00 01 00 00 c3",
            "55 89 f8 c3", "89 fb 89 f8 c3", "eb fe", "89 f8 83 c0 01 75 00 c3"
        )) {
            assertFails { evaluate(code) }
        }
    }
}
