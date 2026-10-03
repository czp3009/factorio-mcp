package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RegisterConstantTest {
    private fun constant(code: String, register: Int = 7, width: Int = 8): Long {
        val flow = X64ControlFlow(X64Instructions(machineCode(code)).all())
        return RegisterConstant(flow)
            .value(flow.instructions.last().offset, Register(register, width))
    }

    @Test
    fun followsCompleteCopiesZeroExtensionAndPreservedRegisters() {
        assertEquals(96L, constant("bf 60 00 00 00 90 c3"))
        assertEquals(96L, constant("b8 60 00 00 00 90 48 89 c7 c3"))
        assertEquals(-1L, constant("48 bf ff ff ff ff ff ff ff ff c3"))
        assertEquals(0xffffffffL, constant("bf ff ff ff ff c3"))
        assertEquals(255L, constant("b0 ff 0f b6 f8 c3"))
        assertEquals(0L, constant("31 ff 90 c3"))
        assertEquals(96L, constant("41 bc 60 00 00 00 e8 00 10 00 00 44 89 e7 c3"))
    }

    @Test
    fun comparesValuesAcrossDifferentIncomingDefinitions() {
        val branch = "85 c0 74 07 bf 60 00 00 00 eb 05 bf 60 00 00 00 c3"
        assertEquals(96L, constant(branch))
        assertFailsWith<IllegalStateException> {
            constant(branch.replace("eb 05 bf 60", "eb 05 bf 61"))
        }
    }

    @Test
    fun rejectsUnknownBitsAndNativeClobbers() {
        for (code in
            listOf(
                "40 b7 60 c3",
                "66 bf 60 00 c3",
                "48 8b 3e c3",
                "bf 60 00 00 00 83 c7 01 c3",
                "bf 60 00 00 00 e8 00 10 00 00 c3",
            )) {
            if (code.startsWith("40") || code.startsWith("66")) {
                assertFailsWith<IllegalArgumentException> { constant(code) }
            } else {
                assertFailsWith<IllegalStateException> { constant(code) }
            }
        }
        assertFailsWith<IllegalArgumentException> { constant("90 c3") }
    }
}
