package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class InputMaskExpressionTest {
    @Test
    fun requiresNativeTypeGuardsAndAnUntransformedBoundedBitIndex() {
        val header = EventHeader(64, 12, 24)
        fun input(offset: Long, width: Int = 4, argument: Int = 6) = ScalarExpression.Input(
            100 + offset,
            SysVArgumentFlow.Read(SysVArgumentFlow.Reference(argument, offset), width), width
        )

        val one = ScalarExpression.Literal(1, 4)
        fun expression(
            code: ScalarExpression.Value, operation: Operation = Operation.SHL,
            fallback: ScalarExpression.Value = one
        ): ScalarExpression.Value = ScalarExpression.Narrow(
            ScalarExpression.Select(
                4, input(header.type), ScalarExpression.Literal(31, 4),
                ScalarExpression.Binary(operation, one, ScalarExpression.Narrow(code, 1), 4), fallback, 4
            ), 2
        )
        assertEquals(40L, InputMaskExpression.validate(header, expression(input(40))))
        assertFails { InputMaskExpression.validate(header, expression(input(40, argument = 7))) }
        assertFails { InputMaskExpression.validate(header, expression(input(40, width = 2))) }
        assertFails { InputMaskExpression.validate(header, expression(input(13))) }
        assertFails { InputMaskExpression.validate(header, expression(input(24))) }
        assertFails { InputMaskExpression.validate(header, expression(input(63))) }
        assertFails { InputMaskExpression.validate(header, expression(input(40), Operation.ADD)) }
        assertFails {
            InputMaskExpression.validate(
                header,
                expression(input(40), fallback = ScalarExpression.Literal(0, 4))
            )
        }
        assertFails {
            InputMaskExpression.validate(
                header, expression(
                    ScalarExpression.Binary(
                        Operation.ADD,
                        input(40), one, 4
                    )
                )
            )
        }
        assertFails {
            InputMaskExpression.validate(header, expression(input(40)).let {
                (it as ScalarExpression.Narrow).copy(width = 4)
            })
        }
    }
}
