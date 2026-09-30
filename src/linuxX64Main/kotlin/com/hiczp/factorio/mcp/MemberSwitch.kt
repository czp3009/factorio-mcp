package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A byte discriminator selecting one bounded observation of the same original object. */
internal class MemberSwitch(
    private val flow: X64ControlFlow,
    private val tables: List<X64JumpTables.Table>,
    private val receiver: Int,
    extent: Long,
    private val type: Long,
    private val code: Long,
) {
    private val arguments = SysVArgumentFlow(flow)
    private val scalars = ScalarExpression(flow, mapOf(receiver to extent), arguments)

    init {
        require(receiver in listOf(7, 6, 2, 1, 8, 9))
        require(extent in 8..4096 && type in 0 until extent && code in 0..extent - 4 && type !in code until code + 4)
        require(tables.all { flow.successors[it.jump]?.toSet() == it.targets.toSet() })
        for (instruction in flow.instructions.filter { it.offset in flow.reachable }) {
            if (instruction.operation in setOf(Operation.CMP, Operation.TEST, Operation.BIT_TEST)) continue
            val destinations = listOfNotNull(
                instruction.destination as? Memory,
                (instruction.source as? Memory).takeIf { instruction.operation == Operation.XCHG })
            require(destinations.none { arguments.memory(instruction.offset, it)?.reference?.argument == receiver }) {
                "Discriminator function writes to its original source object"
            }
        }
    }

    private fun input(value: ScalarExpression.Value, field: Long, width: Int): ScalarExpression.Input {
        var result = value
        while (result is ScalarExpression.Narrow) {
            require(result.width >= width && result.value.width >= width)
            result = result.value
        }
        require(
            result is ScalarExpression.Input && result.width == width &&
                    result.field == SysVArgumentFlow.Read(SysVArgumentFlow.Reference(receiver, field), width)
        ) {
            "Switch call does not forward the original bounded member"
        }
        return result
    }

    fun code(site: Long, register: Register) {
        require(register.width == 4)
        input(scalars.before(site, register), code, 4)
    }

    fun case(site: Long): Int {
        require(site in flow.reachable)
        fun bias(value: ScalarExpression.Value): Long = when (value) {
            is ScalarExpression.Narrow -> {
                require(value.width in listOf(1, 4) && value.width >= value.value.width)
                bias(value.value)
            }

            is ScalarExpression.Binary -> {
                require(
                    value.width == 4 && value.left.width == 4 && value.operation in listOf(
                        Operation.ADD,
                        Operation.SUB
                    )
                )
                val number =
                    value.right as? ScalarExpression.Literal ?: error("Discriminator adjustment is not constant")
                require(number.width == 4 && number.value in -255..255)
                (bias(value.left) + if (value.operation == Operation.ADD) number.value else -number.value)
                    .also { require(it in -255..255) }
            }

            else -> {
                input(value, type, 1)
                0
            }
        }

        val matching = tables.mapNotNull { table ->
            try {
                table to bias(scalars.before(table.guard, table.index.copy(width = minOf(table.index.width, 4))))
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        }
        val (table, adjustment) = matching.singleOrNull()
            ?: error("Switch does not select the original byte discriminator")
        fun reaches(start: Long, omit: Pair<Long, Long>? = null): Boolean {
            val seen = mutableSetOf<Long>()
            val pending = ArrayDeque<Long>()
            pending.add(start)
            while (pending.isNotEmpty()) {
                val cursor = pending.removeFirst()
                if (cursor == site) return true
                if (seen.add(cursor)) pending.addAll(flow.successors.getValue(cursor).filter {
                    (cursor to it) != omit && (cursor != table.jump || start == 0L)
                })
            }
            return false
        }

        val indices = table.targets.indices.filter { reaches(table.targets[it]) }
        val kind = indices.singleOrNull() ?: error("Named site is reachable from multiple discriminator cases")
        require(!reaches(0, table.jump to table.targets[kind])) { "Named site bypasses its discriminator case" }
        return (kind - adjustment).also { require(it in 0..255) }.toInt()
    }
}
