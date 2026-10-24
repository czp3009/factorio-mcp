package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PointerWheelValueTest {
    @Test
    fun provesEveryVerticalDeltaIncludingZeroAndRejectsSampleOnlyEquivalence() {
        val x = ScalarExpression.Input(1, SdlEvents.field(SdlEvents.WHEEL_X, 4), 4)
        val y = ScalarExpression.Input(2, SdlEvents.field(SdlEvents.WHEEL_Y, 4), 4)
        val zero = ScalarExpression.Literal(0, 4)
        val horizontal = ScalarExpression.Binary(Operation.SUB, zero, x, 4)
        val delta =
            ScalarExpression.Select(
                5,
                ScalarExpression.Binary(Operation.AND, y, y, 4),
                zero,
                y,
                horizontal,
                4,
            )
        assertEquals(
            PointerEventPayloads.Companion.WheelValue.VERTICAL,
            PointerEventPayloads.wheelValue(delta),
        )
        assertEquals(
            PointerEventPayloads.Companion.WheelValue.ZERO,
            PointerEventPayloads.wheelValue(horizontal),
        )
        val sampled = ScalarExpression.Binary(Operation.AND, y, ScalarExpression.Literal(1, 4), 4)
        assertFails { PointerEventPayloads.wheelValue(sampled) }
        assertFails { PointerEventPayloads.wheelValue(y.copy(field = SdlEvents.field(24, 4))) }
    }
}
