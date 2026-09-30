package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class GraphicsSwapCallTest {
    @Test
    fun derivesTheSelectedDeviceSlotAndExactReturnSite() {
        val code = "48 8b 3d f9 0f 00 00 48 89 c6 ff 97 d8 00 00 00 c3"
        fun inspect(bytes: String, device: Long = 0x2000) = GraphicsSwapCall.analyze(
            X64Instructions(machineCode(bytes)).all(64), 0x1000, device
        )
        assertEquals(GraphicsSwapCall(0x2000, 216, 0x1010), inspect(code))
        assertEquals(224, inspect(code.replace("d8 00", "e0 00")).member)
        for (invalid in listOf(
            code.replace("8b 3d", "8d 3d"),
            code.replace("48 89 c6", "48 89 ce"),
            code.replace("ff 97", "ff 96"),
            code.replace("d8 00", "d9 00"),
            code.replace("48 89 c6", "89 c6"),
        )) assertFails { inspect(invalid) }
        assertFails { inspect(code, 0x2008) }
        val instructions = X64Instructions(machineCode(code)).all(64)
        assertFails { GraphicsSwapCall.analyze(instructions + instructions, 0x1000, 0x2000) }
        val before = X64Instructions(
            machineCode("48 f7 e6 " + code.replace("f9 0f", "f6 0f")),
            allowUnsignedWideMultiply = true
        ).all(64)
        assertEquals(
            GraphicsSwapCall(0x2000, 216, 0x1013),
            GraphicsSwapCall.analyze(before, 0x1000, 0x2000)
        )
        val interrupted = X64Instructions(
            machineCode(code.replace("48 89 c6", "48 f7 e6 48 89 c6")),
            allowUnsignedWideMultiply = true
        ).all(64)
        assertFails { GraphicsSwapCall.analyze(interrupted, 0x1000, 0x2000) }
    }
}
