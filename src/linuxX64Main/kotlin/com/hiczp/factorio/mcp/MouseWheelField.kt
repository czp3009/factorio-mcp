package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** The native wheel-up handler forwards the event delta to its typed wheelScrollUp(int) method. */
internal object MouseWheelField {
    fun resolve(image: ElfImage, extent: Int): Long {
        val owner = "N4agui9ScrollBarINS_14VerticalPolicyEEE"
        val function =
            image.symbol(
                "_ZN4agui9ScrollBarINS_14VerticalPolicyEE12mouseWheelUpERKNS_10MouseEventE"
            )
        val method =
            ItaniumVtable.resolve(image, "_ZTV$owner")
                .method(image, "_ZN4agui9ScrollBarINS_14VerticalPolicyEE13wheelScrollUpEi")
        return analyze(X64ControlFlow.resolve(image, function), method.slot, extent)
    }

    fun analyze(flow: X64ControlFlow, slot: Int, extent: Int): Long {
        require(slot in 0..4095 && extent in 4..256)
        val pointers = OriginalPointerOrigins(flow)
        val scalars = ScalarExpression(flow, mapOf(6 to extent.toLong()))
        val calls =
            flow.instructions.filter { instruction ->
                val target = instruction.destination as? Memory
                instruction.offset in flow.reachable &&
                    instruction.operation == Operation.CALL &&
                    target != null &&
                    target.width == 8 &&
                    !target.relative &&
                    target.index == null &&
                    target.displacement == slot * 8L &&
                    target.base
                        ?.let { pointers.register(instruction.offset, it) }
                        ?.let {
                            it is OriginalPointerOrigins.Load &&
                                it.base == OriginalPointerOrigins.Argument(7) &&
                                it.member == 0L
                        } == true
            }
        require(calls.isNotEmpty())
        return calls
            .map { call ->
                require(pointers.register(call.offset, 7) == OriginalPointerOrigins.Argument(7))
                var expression = scalars.before(call.offset, Register(6, 4))
                while (expression is ScalarExpression.Narrow) {
                    require(expression.width == 4 && expression.value.width == 4)
                    expression = expression.value
                }
                val value =
                    expression as? ScalarExpression.Input
                        ?: error("Wheel handler transforms or replaces its native event delta")
                require(value.field.width == 4 && value.field.reference.argument == 6)
                value.field.reference.offset
            }
            .distinct()
            .single()
    }
}
