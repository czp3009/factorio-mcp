package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * One straight-line store block in a typed deque element, bounded by its independently derived
 * stride.
 */
internal object IndexedEventStores {
    fun analyze(
        flow: X64ControlFlow,
        header: EventHeader,
        store: Instruction,
        queueCalls: Set<Long>,
        privateLocals: Boolean = false,
        followJumps: Boolean = false,
    ): List<Instruction> {
        val memory = store.destination as? Memory ?: error("Event payload is not stored to memory")
        require(
            !memory.relative &&
                memory.base != null &&
                memory.index != null &&
                memory.base != memory.index
        )
        val definitions = ScalarExpression(flow)
        val arguments = SysVArgumentFlow(flow)
        val frame = if (privateLocals) SysVLocalArgument(flow) else null
        if (privateLocals) ConstructorValues(flow, emptyMap())
        fun stride(site: Long, register: Int, depth: Int = 0): Long {
            require(depth < 16)
            val instruction = definitions.definition(site, register)
            val target = instruction.destination as Register
            require(target.number == register)
            if (target.width == 4) {
                require(
                    instruction.operation in
                        listOf(
                            Operation.MOV,
                            Operation.MOVZX,
                            Operation.ADD,
                            Operation.SUB,
                            Operation.AND,
                            Operation.OR,
                            Operation.XOR,
                            Operation.INC,
                            Operation.DEC,
                        )
                )
                return 1
            }
            require(target.width == 8)
            if (instruction.operation == Operation.MULTIPLY_IMMEDIATE) {
                val input =
                    instruction.source as? Register
                        ?: error("Event stride does not multiply a scalar index")
                val factor =
                    instruction.immediate ?: error("Event stride multiplier is not constant")
                require(input.width == 8 && factor in 1..4096)
                val previous = stride(instruction.offset, input.number, depth + 1)
                require(previous <= 4096 / factor)
                return previous * factor
            }
            val factor =
                when (instruction.operation) {
                    Operation.SHL -> {
                        val count =
                            (instruction.source as? Immediate)?.value
                                ?: error("Variable event stride")
                        require(count in 0..12)
                        1L shl count.toInt()
                    }

                    Operation.LEA -> {
                        val source = instruction.source as Memory
                        require(
                            !source.relative &&
                                source.displacement == 0L &&
                                source.base == register &&
                                source.index == register &&
                                source.scale in listOf(1, 2, 4, 8)
                        )
                        1L + source.scale
                    }

                    else -> error("Unsupported event stride")
                }
            val previous = stride(instruction.offset, register, depth + 1)
            require(previous <= 4096 / factor)
            return previous * factor
        }

        val base = definitions.definition(store.offset, memory.base)
        require(base.operation == Operation.MOV && base.destination == Register(memory.base, 8))
        val queue = checkNotNull(arguments.source(base.offset))
        require(queue.width == 8 && queue.reference.offset in 0..4096 - 8)
        require(
            queueCalls.any {
                it in flow.reachable &&
                    flow.body[it]?.operation == Operation.CALL &&
                    arguments.register(it, 7) == queue.reference.copy(offset = 0)
            }
        )
        require(stride(store.offset, memory.index) * memory.scale == header.extent.toLong())
        val index = definitions.definition(store.offset, memory.index)
        val writes = mutableListOf<Instruction>()
        var site = index.offset + index.size
        require(flow.predecessors[site] == setOf(index.offset))
        val visited = mutableSetOf<Long>()
        repeat(64) {
            require(visited.add(site)) { "Event store block loops" }
            val instruction = flow.body.getValue(site)
            if (followJumps && instruction.operation == Operation.JMP) {
                val next =
                    (instruction.destination as? Immediate)?.value
                        ?: error("Indirect event store continuation")
                require(next in flow.body && flow.predecessors[next] == setOf(site))
                site = next
                return@repeat
            }
            if (
                instruction.operation in
                    listOf(Operation.CALL, Operation.JCC, Operation.JMP, Operation.RET)
            ) {
                require(store in writes)
                return writes
            }
            if (
                (privateLocals || followJumps) &&
                    instruction.operation == Operation.ADD &&
                    instruction.destination == Register(4, 8)
            ) {
                require(store in writes)
                return writes
            }
            val output = instruction.destination as? Memory
            if (
                output != null &&
                    instruction.operation !in
                        listOf(Operation.CMP, Operation.TEST, Operation.SCALAR_COMPARE)
            ) {
                require(
                    instruction.operation in
                        listOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV)
                )
                val local = frame?.address(site, output)
                if (local != null) {
                    require(
                        local >= checkNotNull(checkNotNull(frame).registers(site)[4]) &&
                            local <= -output.width
                    )
                    site += instruction.size
                    require(flow.predecessors[site] == setOf(instruction.offset))
                    return@repeat
                }
                require(
                    output.copy(width = memory.width, displacement = memory.displacement) ==
                        memory &&
                        definitions.definition(site, memory.base) == base &&
                        definitions.definition(site, memory.index) == index
                )
                require(
                    output.width in 1..16 &&
                        output.displacement >= 0 &&
                        output.displacement <= header.extent - output.width
                )
                writes += instruction
            }
            val next = site + instruction.size
            require(
                flow.successors.getValue(site) == listOf(next) &&
                    flow.predecessors[next] == setOf(site)
            ) {
                "Event store block has an intervening control-flow entry"
            }
            site = next
        }
        error("Event store block exceeds bound")
    }
}
