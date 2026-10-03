package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Bounded pointer-copy paths from exact call results; callers independently identify the returned object's type. */
internal class ReturnedPointerOrigins(private val flow: X64ControlFlow) {
    data class Value(val call: Long, val members: List<Long> = emptyList(), val adjustment: Long = 0)

    private val values = mutableMapOf<Pair<Long, Int>, Value?>()
    private val active = mutableSetOf<Pair<Long, Int>>()
    private var work = 0

    fun register(site: Long, number: Int): Value? {
        require(site in flow.reachable && number in 0..15)
        val key = site to number
        if (key in values) return values[key]
        require(++work <= 32768 && active.size < 128 && active.add(key)) { "Returned pointer slice is cyclic or exceeds bounds" }
        try {
            val incoming = flow.predecessors[site].orEmpty().map { previous ->
                val instruction = flow.body.getValue(previous)
                if (instruction.operation == Operation.ATOMIC_EXCHANGE_ADD &&
                    (instruction.source as? Register)?.number == number) return@map null
                if (instruction.operation == Operation.CALL) return@map when {
                    number == 0 -> Value(previous)
                    number in listOf(1, 2, 6, 7, 8, 9, 10, 11) -> null
                    else -> register(previous, number)
                }
                val destination = instruction.destination as? Register
                if (destination?.number != number || instruction.operation in listOf(Operation.CMP, Operation.TEST,
                    Operation.JMP, Operation.JCC, Operation.PUSH, Operation.NOP, Operation.ENDBR, Operation.RET))
                    return@map register(previous, number)
                if (destination.width != 8) return@map null
                when (instruction.operation) {
                    Operation.MOV -> when (val source = instruction.source) {
                        is Register -> if (source.width == 8) register(previous, source.number) else null
                        is Memory -> if (source.width == 8) address(previous, source)?.let {
                            if (it.members.size < 4 && it.adjustment in 0..65528)
                                it.copy(members = it.members + it.adjustment, adjustment = 0) else null
                        } else null
                        else -> null
                    }
                    Operation.LEA -> (instruction.source as? Memory)?.let { address(previous, it) }
                    Operation.ADD, Operation.SUB -> (instruction.source as? Immediate)?.value?.let { amount ->
                        register(previous, number)?.let { adjusted(it, if (instruction.operation == Operation.SUB) -amount else amount) }
                    }
                    Operation.CMOV -> (instruction.source as? Register)?.takeIf { it.width == 8 }?.let { source ->
                        register(previous, number)?.takeIf { it == register(previous, source.number) }
                    }
                    else -> null
                }
            }
            return incoming.firstOrNull()?.takeIf { value -> incoming.all { it == value } }.also { values[key] = it }
        } finally {
            active.remove(key)
        }
    }

    fun address(site: Long, memory: Memory): Value? {
        if (memory.relative || memory.index != null || memory.base == null) return null
        return register(site, memory.base)?.let { adjusted(it, memory.displacement) }
    }

    private fun adjusted(value: Value, amount: Long): Value? =
        if (amount in -65536..65536 && value.adjustment + amount in -65536..65536)
            value.copy(adjustment = value.adjustment + amount) else null
}
