package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Exact pointer-load origins on normal entry paths. Expressions do not authorize dereferences or lifetimes. */
internal class OriginalPointerOrigins(private val flow: X64ControlFlow, private val address: Long = 0) {
    sealed interface Value

    data class Argument(val register: Int, val adjustment: Long = 0) : Value

    data class Global(val address: Long, val site: Long) : Value

    data class Load(val base: Value, val member: Long, val site: Long) : Value

    data class Adjusted(val base: Value, val amount: Long) : Value

    private val before = mutableMapOf<Long, List<Value?>>()

    init {
        require(address >= 0 && address <= Long.MAX_VALUE - flow.instructions.last().offset - 15)
        before[0] = List(16) { if (it in listOf(7, 6, 2, 1, 8, 9)) Argument(it) else null }
        val pending = ArrayDeque<Long>()
        pending.add(0)
        var steps = 0
        while (pending.isNotEmpty()) {
            require(++steps <= 262144) { "Original pointer analysis exceeds bounds" }
            val site = pending.removeFirst()
            val values = before.getValue(site).toMutableList()
            val instruction = flow.body.getValue(site)
            fun read(operand: Operand?): Value? = (operand as? Register)?.takeIf { it.width == 8 && it.number in 0..15 }
                ?.let { values[it.number] }
            fun write(operand: Operand?, value: Value?) {
                if (operand is Register && operand.number in 0..15)
                    values[operand.number] = value.takeIf { operand.width == 8 }
            }
            when (instruction.operation) {
                Operation.MOV -> write(instruction.destination, when (val source = instruction.source) {
                    is Register -> read(source)
                    is Memory -> when {
                        source.width != 8 || source.index != null -> null
                        source.relative && source.base == null -> {
                            val next = address + instruction.offset + instruction.size
                            require(source.displacement >= -next && source.displacement <= Long.MAX_VALUE - next)
                            Global(next + source.displacement, site)
                        }
                        !source.relative -> source.base?.let { values[it] }?.let { Load(it, source.displacement, site) }
                        else -> null
                    }
                    else -> null
                })
                Operation.LEA -> write(instruction.destination, (instruction.source as? Memory)
                    ?.takeIf { !it.relative && it.index == null }
                    ?.let { memory -> memory.base?.let { values[it] }?.let { adjusted(it, memory.displacement) } })
                Operation.ADD, Operation.SUB -> write(instruction.destination,
                    (instruction.source as? Immediate)?.value?.let { amount -> read(instruction.destination)
                        ?.let { adjusted(it, if (instruction.operation == Operation.SUB) -amount else amount) } })
                Operation.CMOV -> write(instruction.destination,
                    read(instruction.destination)?.takeIf { it == read(instruction.source) })
                Operation.XCHG -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    write(instruction.destination, right)
                    write(instruction.source, left)
                }
                Operation.ATOMIC_EXCHANGE_ADD -> write(instruction.source, null)
                Operation.CALL -> for (number in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) values[number] = null
                Operation.CMP, Operation.TEST, Operation.BIT_TEST, Operation.JMP, Operation.JCC,
                Operation.PUSH, Operation.NOP, Operation.ENDBR, Operation.RET -> Unit
                else -> write(instruction.destination, null)
            }
            for (next in flow.successors.getValue(site)) {
                // A repeated load instruction does not establish the same object in another iteration.
                val outgoing = if (next <= site) values.map { if (it is Argument) it else null } else values
                val old = before[next]
                val merged = if (old == null) outgoing.toList() else old.mapIndexed { number, value ->
                    value.takeIf { it == outgoing[number] }
                }
                if (old != merged) {
                    before[next] = merged
                    pending.add(next)
                }
            }
        }
    }

    fun register(site: Long, number: Int): Value? {
        require(site in flow.reachable) { "Pointer origin has no normal entry path" }
        require(number in 0..15) { "Pointer origin is not a general-purpose register" }
        return before.getValue(site)[number]
    }

    private fun adjusted(value: Value, amount: Long): Value? {
        if (amount !in -65536..65536) return null
        return when (value) {
            is Argument -> value.copy(adjustment = value.adjustment + amount).takeIf { it.adjustment in -65536..65536 }
            is Adjusted -> Adjusted(value.base, value.amount + amount).takeIf { it.amount in -65536..65536 }
            else -> if (amount == 0L) value else Adjusted(value, amount)
        }
    }
}
