package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class FramebufferDimensionTest {
    private fun getter(first: Int, second: Int, guard: String = "74", output: String = "8b 40 18"): X64ControlFlow =
        X64ControlFlow(X64Instructions(machineCode(
            "55 48 89 e5 48 8b 47 ${first.toString(16)} 48 85 c0 75 09 " +
                    "48 8b 47 ${second.toString(16)} 48 85 c0 $guard 05 $output 5d c3 e8 00 01 00 00"
        )).all())

    @Test
    fun checksBothNonnullPathsAndTheScalarCallingBoundary() {
        for ((first, second) in listOf(16 to 32, 40 to 64)) {
            assertEquals(FramebufferDimension(first.toLong(), second.toLong()), FramebufferDimension.analyze(getter(first, second), 128))
            assertFails { FramebufferDimension.analyze(getter(first, second), second + 7L) }
            assertFails { FramebufferDimension.analyze(getter(first, first), 128) }
            assertFails { FramebufferDimension.analyze(getter(first, second, guard = "75"), 128) }
            assertFails { FramebufferDimension.analyze(getter(first, second, output = "8b 48 18"), 128) }
            assertFails { FramebufferDimension.analyze(getter(first, second, output = "89 40 18"), 128) }
        }
    }
}
