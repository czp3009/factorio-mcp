package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertFails

class ChatActionDestructionTest {
    private val string = NativeStringLayout(0, 8, 16, 32, ElfImage.Symbol("fixture_destroy", 0x2000, 1, 2, 1))
    private val code = "55 48 89 e5 0f b7 47 0c 83 e8 05 83 f8 02 77 2c " +
            "48 8d 0d e9 00 00 00 48 63 04 81 48 01 c8 ff e0 " +
            "48 8b 47 20 48 83 c7 30 48 39 f8 74 0f 48 8b 37 48 ff c6 48 89 c7 5d e9 c4 01 00 00 5d c3"

    private fun verify(text: String = code, kind: Int = 6, payload: Long = 32) {
        val instructions = X64Instructions(machineCode(text)).all()
        val tables = X64JumpTables.resolve(instructions, 0x1000) { address, size ->
            require(address == 0x1100L && size == 12L)
            BinaryView(listOf(60L, 32L, 60L).flatMap { target ->
                val displacement = 0x1000 + target - address
                List(4) { (displacement ushr (it * 8)).toByte() }
            }.toByteArray())
        }
        ChatActionDestruction.analyze(
            X64ControlFlow(instructions, tables), tables, 0x1000, 0x1200,
            96, kind, 12, payload, string
        )
    }

    @Test
    fun selectsNativeCaseAndProvesInlineAndSizedHeapDestruction() = verify()

    @Test
    fun rejectsWrongCasePointerCapacityAndFrame() {
        for (kind in listOf(4, 5, 7, 8)) assertFails { verify(kind = kind) }
        assertFails { verify(payload = 40) }
        for (invalid in listOf(
            code.replace("47 0c", "47 0e"),
            code.replace("47 20", "47 28"),
            code.replace("c7 30", "c7 38"),
            code.replace("48 ff c6", "48 ff ce"),
            code.replace("48 89 c7", "48 89 f7"),
            code.replace("5d e9", "5b e9"),
            code.replace("e9 c4", "e9 c5"),
            code.replace("74 0f", "75 0f"),
        )) assertFails { verify(invalid) }
    }
}
