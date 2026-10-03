package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SdlButtonAdmission.Button
import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class MouseButtonMasksTest {
    @Test
    fun connectsTypedEventKindsAndNativeButtonCodesWithoutReusingHeldStateMasks() {
        val header = EventHeader(64, 12, 24)
        fun input(offset: Long) = ScalarExpression.Input(
            100 + offset,
            SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, offset), 4), 4
        )

        val one = ScalarExpression.Literal(1, 4)
        val expression = InputMaskExpression(
            100, 4, 40, ScalarExpression.Narrow(
                ScalarExpression.Select(
                    4, input(header.type), ScalarExpression.Literal(99, 4), one,
                    ScalarExpression.Binary(Operation.SHL, one, ScalarExpression.Narrow(input(40), 1), 4), 4
                ), 2
            )
        )
        val codes = mapOf(Button.LEFT to 3, Button.MIDDLE to 7, Button.RIGHT to 5, Button.X1 to 2, Button.X2 to 6)
        assertEquals(
            mapOf(Button.LEFT to 8, Button.MIDDLE to 128, Button.RIGHT to 32, Button.X1 to 4, Button.X2 to 64),
            MouseButtonMasks.values(expression, header, setOf(17, 18), codes)
        )
        assertFails { MouseButtonMasks.values(expression, header, setOf(17), codes) }
        assertFails { MouseButtonMasks.values(expression, header, setOf(17, 99), codes) }
        assertFails { MouseButtonMasks.values(expression, header, setOf(17, 18), codes - Button.LEFT) }
        for (code in listOf(0, 7, 16, 32, 256)) assertFails {
            MouseButtonMasks.values(expression, header, setOf(17, 18), codes + (Button.LEFT to code))
        }
        assertFails { MouseButtonMasks.values(expression.copy(code = 44), header, setOf(17, 18), codes) }
    }
}
