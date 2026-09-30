package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Owner members receiving a pointer after a concrete primary-vptr initialization in its constructor.
 * Locations are candidates: a runtime reader must check the pointee's exact primary type and reject ambiguity.
 * This establishes neither allocation size nor callable constructor ABI, and never retains an object pointer.
 */
internal object TypedMemberStores {
    private sealed interface Value
    private data object Unknown : Value
    private data object Table : Value
    private data class Object(val initialization: Long) : Value

    fun resolve(image: ElfImage, constructor: String, ownerSize: Long, type: ItaniumType): List<Long> {
        val function = image.symbol(constructor)
        return analyze(X64ControlFlow.resolve(image, function), function.address, ownerSize, type.addressPoint)
    }

    fun analyze(flow: X64ControlFlow, address: Long, ownerSize: Long, table: Long): List<Long> {
        val end = flow.instructions.last().let { it.offset + it.size }
        require(
            address >= 0 && address <= Long.MAX_VALUE - end && ownerSize in 8..(64 * 1024 * 1024) &&
                    table > 0 && table % 8 == 0L
        )
        val owner = SysVArgumentFlow(flow)
        val before = mutableMapOf<Long, List<Value>>(0L to List(32) { Unknown })
        val pending = ArrayDeque<Long>()
        pending.add(0)
        var steps = 0
        while (pending.isNotEmpty()) {
            require(++steps <= 262144) { "Typed member provenance exceeds bound" }
            val site = pending.removeFirst()
            val values = before.getValue(site).toMutableList()
            val instruction = flow.body.getValue(site)
            fun read(operand: X64Instructions.Operand?): Value =
                (operand as? Register)?.takeIf { it.width == 8 }?.let { values[it.number] } ?: Unknown

            fun write(operand: X64Instructions.Operand?, value: Value) {
                if (operand is Register) values[operand.number] = if (operand.width == 8) value else Unknown
            }
            when (instruction.operation) {
                Operation.MOV -> {
                    val target = instruction.destination
                    val source = read(instruction.source)
                    if (target is Register) write(target, source)
                    else if (target is Memory && source == Table) {
                        require(
                            target.width == 8 && !target.relative && target.index == null &&
                                    target.displacement == 0L && target.base != null && target.base !in listOf(4, 5)
                        ) {
                            "Concrete primary vptr does not initialize an unadjusted pointer"
                        }
                        require(owner.memory(site, target)?.reference?.argument != 7) {
                            "Selected child type replaces the original owner's vptr"
                        }
                        values[target.base] = Object(site)
                    }
                }

                Operation.LEA -> {
                    val memory = instruction.source as? Memory ?: error("Invalid typed construction address")
                    val next = address + site + instruction.size
                    val expected = memory.relative && memory.base == null && memory.index == null &&
                            memory.displacement >= -next && memory.displacement <= Long.MAX_VALUE - next &&
                            next + memory.displacement == table
                    write(instruction.destination, if (expected) Table else Unknown)
                }

                Operation.CALL -> for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)) values[register] =
                    Unknown

                Operation.PUSH -> values[4] = Unknown
                Operation.POP -> {
                    values[4] = Unknown
                    write(instruction.destination, Unknown)
                }

                Operation.XCHG -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    write(instruction.destination, right)
                    write(instruction.source, left)
                }

                Operation.CMOV -> write(
                    instruction.destination,
                    read(instruction.destination).takeIf { it == read(instruction.source) } ?: Unknown)

                Operation.CMP, Operation.TEST, Operation.JCC, Operation.JMP, Operation.RET,
                Operation.NOP, Operation.ENDBR, Operation.BIT_TEST, Operation.SCALAR_COMPARE -> Unit

                else -> write(instruction.destination, Unknown)
            }
            // A later write that could replace the primary vptr destroys this construction evidence.
            val target = instruction.destination as? Memory
            val objectValue = target?.base?.let { before.getValue(site)[it] } as? Object
            if (objectValue != null && instruction.operation !in setOf(
                    Operation.CMP,
                    Operation.TEST,
                    Operation.BIT_TEST
                ) &&
                (target.index != null || target.displacement < 8 && target.displacement + target.width > 0)
            ) {
                for (index in values.indices) if (values[index] == objectValue) values[index] = Unknown
            }
            for (next in flow.successors.getValue(site)) {
                // A repeated construction site can denote a different allocation. Do not merge its identities.
                val outgoing = if (next <= site) values.map { if (it is Object) Unknown else it } else values
                val old = before[next]
                val merged = if (old == null) outgoing.toList() else old.mapIndexed { index, value ->
                    if (value == outgoing[index]) value else Unknown
                }
                if (old != merged) {
                    before[next] = merged
                    pending.add(next)
                }
            }
        }
        val members = flow.instructions.mapNotNull { instruction ->
            if (instruction.offset !in flow.reachable || instruction.operation != Operation.MOV) return@mapNotNull null
            val memory = instruction.destination as? Memory ?: return@mapNotNull null
            val source = instruction.source as? Register ?: return@mapNotNull null
            if (before[instruction.offset]?.get(source.number) !is Object) return@mapNotNull null
            val reference = owner.memory(instruction.offset, memory) ?: return@mapNotNull null
            if (reference.reference.argument != 7) return@mapNotNull null
            require(
                memory.width == 8 && source.width == 8 && reference.reference.offset in 0..ownerSize - 8 &&
                        reference.reference.offset % 8 == 0L
            )
            reference.reference.offset
        }.distinct().sorted()
        require(members.size in 1..64) { "Constructor has no bounded set of typed child members" }
        return members
    }
}
