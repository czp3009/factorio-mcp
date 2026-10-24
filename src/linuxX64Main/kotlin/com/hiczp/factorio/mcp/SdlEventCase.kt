package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Specializes only conditions proved to depend on the public SDL event type. Queue/state branches
 * remain live.
 */
internal object SdlEventCase {
    fun select(
        flow: X64ControlFlow,
        tables: List<X64JumpTables.Table>,
        type: Long,
    ): X64ControlFlow {
        val scalars = ScalarExpression(flow, mapOf(SdlEvents.ARGUMENT to 4L))
        val field = SdlEvents.field(SdlEvents.TYPE, 4)
        fun evaluate(expression: ScalarExpression.Value): Long {
            require(ScalarExpression.inputs(expression).all { it.field == field })
            return ScalarExpression.evaluate(expression) { type }
        }
        val replacements = mutableMapOf<Long, Instruction>()
        for (table in tables) {
            val index =
                runCatching { evaluate(scalars.before(table.guard, table.index.copy(width = 4))) }
                    .getOrNull() ?: continue
            if (index !in 0 until table.targets.size.toLong()) continue
            val jump = flow.body.getValue(table.jump)
            replacements[jump.offset] =
                jump.copy(destination = Immediate(table.targets[index.toInt()]))
        }
        for (branch in
            flow.instructions.filter {
                it.offset in flow.reachable && it.operation == Operation.JCC
            }) {
            val decision =
                runCatching { evaluate(scalars.branch(branch.offset)) }.getOrNull() ?: continue
            val next =
                if (decision != 0L) (branch.destination as Immediate).value
                else branch.offset + branch.size
            replacements[branch.offset] =
                branch.copy(
                    operation = Operation.JMP,
                    destination = Immediate(next),
                    source = null,
                    condition = null,
                )
        }
        return X64ControlFlow(
            flow.instructions.map { replacements[it.offset] ?: it },
            tables.filter { it.jump !in replacements },
        )
    }
}
