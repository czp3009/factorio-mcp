package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Original arguments in private full-width slots, with explicitly proven disjoint external writes. */
internal class PrivateArgumentOrigins(
    private val flow: X64ControlFlow,
    private val memcpy: Long,
    private val assign: Long,
) {
    private val arguments = SysVArgumentFlow(flow, includeStack = true)
    private val frame = SysVLocalArgument(flow)
    private val registers = mutableMapOf<Pair<Long, Int>, SysVArgumentFlow.Reference?>()
    private val activeRegisters = mutableSetOf<Pair<Long, Int>>()
    private val slots = mutableMapOf<Pair<Long, Long>, SysVArgumentFlow.Reference?>()
    private val activeSlots = mutableSetOf<Pair<Long, Long>>()
    private var work = 0

    fun register(site: Long, number: Int): SysVArgumentFlow.Reference? {
        require(site in flow.reachable && number in 0..15)
        arguments.register(site, number)?.let { return it }
        val key = site to number
        if (key in registers) return registers[key]
        require(++work <= 16384 && activeRegisters.size < 128 && activeRegisters.add(key)) {
            "Private argument register slice is cyclic or exceeds bounds"
        }
        try {
            val values = flow.predecessors[site].orEmpty().map { previous ->
                val instruction = flow.body.getValue(previous)
                val destination = instruction.destination as? Register
                when {
                    instruction.operation == Operation.CALL && number in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) -> null
                    destination?.number != number || instruction.operation in listOf(Operation.CMP, Operation.TEST,
                        Operation.PUSH, Operation.JCC, Operation.JMP, Operation.RET) -> register(previous, number)
                    destination.width != 8 -> null
                    instruction.operation == Operation.MOV -> when (val source = instruction.source) {
                        is Register -> if (source.width == 8) register(previous, source.number) else null
                        is Memory -> if (source.width == 8) frame.address(previous, source)?.let { stored(previous, it) } else null
                        else -> null
                    }
                    instruction.operation == Operation.LEA -> (instruction.source as? Memory)?.let { address(previous, it) }
                    instruction.operation in listOf(Operation.ADD, Operation.SUB) -> {
                        val amount = (instruction.source as? Immediate)?.value
                        if (amount == null) null else register(previous, number)?.let {
                            adjusted(it, if (instruction.operation == Operation.SUB) -amount else amount)
                        }
                    }
                    else -> null
                }
            }
            return values.firstOrNull()?.takeIf { value -> values.all { it == value } }.also { registers[key] = it }
        } finally {
            activeRegisters.remove(key)
        }
    }

    fun address(site: Long, memory: Memory): SysVArgumentFlow.Reference? {
        if (memory.relative || memory.index != null || memory.base == null) return null
        return register(site, memory.base)?.let { adjusted(it, memory.displacement) }
    }

    private fun adjusted(value: SysVArgumentFlow.Reference, amount: Long): SysVArgumentFlow.Reference? =
        if (amount in -65536..65536 && value.offset + amount in -65536..65536)
            value.copy(offset = value.offset + amount) else null

    private fun stored(site: Long, slot: Long): SysVArgumentFlow.Reference? {
        val key = site to slot
        if (key in slots) return slots[key]
        require(++work <= 16384 && activeSlots.size < 128 && activeSlots.add(key)) {
            "Private argument storage slice is cyclic or exceeds bounds"
        }
        try {
            require(slot >= checkNotNull(frame.registers(site)[4]) && slot <= -8)
            val values = flow.predecessors[site].orEmpty().map { previous ->
                val instruction = flow.body.getValue(previous)
                val target = instruction.destination as? Memory
                if (target != null && instruction.operation !in listOf(Operation.CMP, Operation.TEST, Operation.NOP)) {
                    val location = frame.address(previous, target)
                    if (location != null && location < slot + 8 && slot < location + target.width) {
                        require(location == slot && target.width == 8 && instruction.operation == Operation.MOV) {
                            "Private argument slot is partially overwritten"
                        }
                        val source = instruction.source as? Register ?: error("Private argument is replaced by another value")
                        require(source.width == 8)
                        return@map register(previous, source.number)
                    }
                    if (location == null) {
                        val external = address(previous, target)
                        require(external != null && external.argument != 4) { "Private argument may alias an unknown write" }
                        val source = instruction.source as? Register
                        require(source == null || frame.registers(previous).getOrNull(source.number) == null) {
                            "Private argument path publishes a frame address"
                        }
                    }
                }
                if (instruction.operation == Operation.CALL) {
                    when (instruction.destination) {
                        Immediate(memcpy) -> {
                            val destination = frame.registers(previous)[7]
                                ?: error("Private-slot memcpy destination is not in the current frame")
                            require(destination >= slot + 8) { "Forward memcpy can overlap the private argument slot" }
                        }
                        Immediate(assign) -> require(register(previous, 7)?.argument == 8 &&
                                register(previous, 6) == SysVArgumentFlow.Reference(6)) {
                            "Native string assignment borrows unverified storage"
                        }
                        else -> error("Private argument crosses an unsupported call")
                    }
                }
                stored(previous, slot)
            }
            return values.firstOrNull()?.takeIf { value -> values.all { it == value } }.also { slots[key] = it }
        } finally {
            activeSlots.remove(key)
        }
    }

    /** A stored frame alias requires separate tracking if it is ever read before a selected descriptor use. */
    fun validateAliases(site: Long) {
        val prefix = flow.reaching(site)
        val aliases = prefix.instructions.filter { it.offset in prefix.reachable }.mapNotNull { instruction ->
            val target = instruction.destination as? Memory ?: return@mapNotNull null
            val source = instruction.source as? Register ?: return@mapNotNull null
            if (instruction.operation != Operation.MOV || source.width != 8 ||
                frame.registers(instruction.offset).getOrNull(source.number) == null) return@mapNotNull null
            val destination = frame.address(instruction.offset, target)
                ?: error("Private argument descriptor publishes a frame address")
            require(target.width == 8)
            destination
        }
        for (instruction in prefix.instructions) {
            if (instruction.offset !in prefix.reachable || instruction.operation == Operation.LEA) continue
            if (instruction.operation == Operation.CALL) {
                val borrowed = listOf(7, 6, 2, 1, 8, 9).filter { frame.registers(instruction.offset)[it] != null }
                require(borrowed.isEmpty() || instruction.destination == Immediate(memcpy) && borrowed == listOf(7)) {
                    "Private argument path lends a frame address to an unsupported call"
                }
            }
            val source = instruction.source as? Memory ?: continue
            val location = frame.address(instruction.offset, source) ?: continue
            require(aliases.none { location < it + 8 && it < location + source.width }) {
                "Private argument path reloads a stored frame alias"
            }
        }
    }
}
