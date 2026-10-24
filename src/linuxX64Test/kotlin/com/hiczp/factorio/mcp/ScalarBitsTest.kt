package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.*

class ScalarBitsTest {
    @Test
    fun acceptsDecisiveMaskBitsOnEitherSideOfTheOperation() {
        for (code in
            listOf(
                "48 b8 00 00 00 00 ff ff ff ff 4c 21 c0 c3",
                "48 b9 00 00 00 00 ff ff ff ff 48 21 c8 c3",
            )) {
            val body = X64Instructions(machineCode(code)).all()
            val bits = ScalarBits(X64ControlFlow(body), emptyMap())
            for (bit in 0..31) assertEquals(
                ScalarBits.Constant(false),
                bits.operand(body.last().offset, Register(0, 8), bit),
            )
        }
    }

    @Test
    fun provesOnlyCommonConditionalBitsAndCarryFreeLowAdditionBits() {
        val code = "48 c1 e2 20 48 b9 00 00 00 00 ff ff ff 7f 48 0f 44 d1 48 01 ca c3"
        fun bits(code: String): Pair<ScalarBits, Long> {
            val body = X64Instructions(machineCode(code)).all()
            return ScalarBits(X64ControlFlow(body), emptyMap()) to body.last().offset
        }
        val (proof, end) = bits(code)
        for (bit in 0..31) assertEquals(
            ScalarBits.Constant(false),
            proof.operand(end, Register(2, 8), bit),
        )
        assertFails { proof.operand(end, Register(2, 8), 32) }
        val (invalid, invalidEnd) = bits(code.replace("00 00 00 00 ff", "01 00 00 00 ff"))
        assertFails { invalid.operand(invalidEnd, Register(2, 8), 0) }
    }
}
