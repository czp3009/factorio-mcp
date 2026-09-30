package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Header stores from the typed emplace arguments into its returned array element. Does not invoke the constructor. */
internal data class EventHeader(val extent: Int, val type: Long, val time: Long) {
    companion object {
        fun resolve(
            image: ElfImage, name: String =
                "_ZN12CompactDequeI5EventE12emplace_backIJRdNS0_4TypeEEEERS0_DpOT_"
        ): EventHeader {
            val function = image.symbol(name)
            EhFrames(image).function(function)
            require(function.size in 1..4096)
            return analyze(X64ControlFlow.resolve(image, function))
        }

        fun analyze(flow: X64ControlFlow): EventHeader = HeaderFlow(flow).resolve()
    }
}

private class HeaderFlow(private val flow: X64ControlFlow) {
    private sealed interface Value
    private data object Unknown : Value
    private data class Argument(val register: Int) : Value
    private data class Frame(val offset: Long) : Value
    private data class Member(val offset: Long, val loadedAt: Long) : Value
    private data class Scalar(val at: Long, val factor: Long = 1) : Value
    private data class Element(val member: Member, val index: Long, val stride: Long, val offset: Long = 0) : Value
    private data class Field(val argument: Int, val width: Int) : Value
    private data class Store(val element: Element, val width: Int, val field: Field)
    private data class State(val registers: MutableList<Value>, val stores: MutableSet<Store>) {
        fun copyState() = State(registers.toMutableList(), stores.toMutableSet())
    }

    fun resolve(): EventHeader {
        require(flow.instructions.size <= 1024 && flow.instructions.none { it.operation == Operation.MULTIPLY_WIDE })
        require(flow.instructions.all { instruction ->
            instruction.operation !in listOf(Operation.JMP, Operation.JCC) ||
                    instruction.destination !is Immediate || instruction.destination.value in flow.body
        }) { "Event construction branches outside its function" }
        val remaining = flow.reachable.associateWith { flow.predecessors[it].orEmpty().size }.toMutableMap()
        require(remaining.getValue(0) == 0) { "Event header construction loops through its entry" }
        val pending = ArrayDeque<Long>()
        pending.add(0)
        val order = mutableListOf<Long>()
        while (pending.isNotEmpty()) {
            val offset = pending.removeFirst()
            order += offset
            for (next in flow.successors.getValue(offset)) {
                remaining[next] = remaining.getValue(next) - 1
                if (remaining[next] == 0) pending.add(next)
            }
        }
        require(order.size == flow.reachable.size) { "Event header construction contains a cycle" }
        val incoming = mutableMapOf<Long, State>()
        val initial = MutableList<Value>(32) { Unknown }
        for (register in listOf(7, 6, 2)) initial[register] = Argument(register)
        initial[4] = Frame(0)
        incoming[0] = State(initial, mutableSetOf())
        val results = mutableListOf<EventHeader>()
        for (position in order) {
            val instruction = flow.body.getValue(position)
            val state = incoming[position]?.copyState() ?: continue
            val registers = state.registers
            fun top() = (registers[4] as? Frame)?.offset ?: error("Event header has an unknown local frame")
            fun value(operand: X64Instructions.Operand?): Value = when (operand) {
                is Register -> registers[operand.number].let {
                    if (operand.width == 8 || it is Field && it.width == operand.width || it is Scalar) it else Unknown
                }

                else -> Unknown
            }

            fun scaled(value: Scalar, scale: Long): Scalar {
                require(scale in 1..4096 && value.factor in 1..4096 / scale) { "Event element stride exceeds bound" }
                return value.copy(factor = value.factor * scale)
            }

            fun location(memory: Memory): Value {
                if (memory.relative) return Unknown
                val base = memory.base?.let { registers[it] } ?: Unknown
                val index = memory.index?.let { registers[it] }
                if (index != null) {
                    if (index !is Scalar) return Unknown
                    val term = scaled(index, memory.scale.toLong())
                    return when {
                        base is Member && term.factor in 16..4096 ->
                            Element(base, term.at, term.factor, memory.displacement)

                        base is Scalar && base.at == term.at && memory.displacement == 0L -> {
                            require(base.factor + term.factor <= 4096)
                            base.copy(factor = base.factor + term.factor)
                        }

                        else -> Unknown
                    }
                }
                return when (base) {
                    is Argument -> if (memory.displacement == 0L) base else Unknown
                    is Frame -> {
                        require(memory.displacement in -16384..16384)
                        Frame(base.offset + memory.displacement)
                    }

                    is Element -> {
                        require(memory.displacement in -4096..4096)
                        base.copy(offset = base.offset + memory.displacement)
                    }

                    else -> Unknown
                }
            }

            fun read(operand: X64Instructions.Operand?): Value {
                if (operand !is Memory) return value(operand)
                val pointer = location(operand)
                if (pointer == Argument(6) && operand.width == 8) return Field(6, 8)
                if (pointer == Argument(2) && operand.width == 4) return Field(2, 4)
                if (!operand.relative && operand.index == null && operand.base?.let { registers[it] } == Argument(7) &&
                    operand.displacement in 0..4096 - operand.width) {
                    return when (operand.width) {
                        8 -> Member(operand.displacement, position)
                        4 -> Scalar(position)
                        else -> Unknown
                    }
                }
                // Saved locals and untyped external reads cannot manufacture an original argument or array index.
                return Unknown
            }

            fun write(destination: X64Instructions.Operand?, source: Value) {
                when (destination) {
                    is Register -> {
                        val next = when {
                            destination.width == 8 -> source
                            source is Field && source.width == destination.width -> source
                            source is Scalar && destination.width == 4 -> source
                            else -> Unknown
                        }
                        if (destination.number == 4) require(next is Frame && next.offset in -16384..0)
                        registers[destination.number] = next
                    }

                    is Memory -> when (val target = location(destination)) {
                        is Element -> {
                            require(target.offset >= 0 && target.offset <= target.stride - destination.width) {
                                "Event construction writes beyond its derived element stride"
                            }
                            state.stores.removeAll { store ->
                                store.element.copy(offset = 0) != target.copy(offset = 0) ||
                                        store.element.offset < target.offset + destination.width &&
                                        target.offset < store.element.offset + store.width
                            }
                            if (source is Field && source.width == destination.width)
                                state.stores += Store(target, destination.width, source)
                        }

                        is Frame -> {
                            require(target.offset >= top() && target.offset <= -destination.width)
                            // Escaped frame addresses are rejected at calls and external stores below.
                        }

                        else -> {
                            require(source !is Frame) { "Event construction exposes its local frame" }
                            state.stores.clear()
                        }
                    }

                    else -> error("Unsupported event header destination")
                }
            }
            when (instruction.operation) {
                Operation.PUSH -> {
                    registers[4] = Frame(top() - 8)
                    require(top() >= -16384)
                }

                Operation.POP -> {
                    require(instruction.destination is Register && instruction.destination.number != 4)
                    write(instruction.destination, Unknown)
                    registers[4] = Frame(top() + 8)
                }

                Operation.MOV, Operation.MOVZX, Operation.SCALAR_MOV, Operation.VECTOR_MOV ->
                    write(instruction.destination, read(instruction.source))

                Operation.LEA -> write(
                    instruction.destination,
                    location(instruction.source as? Memory ?: error("Invalid event construction address"))
                )

                Operation.ADD, Operation.SUB -> {
                    if (instruction.destination == Register(4, 8)) {
                        val amount =
                            (instruction.source as? Immediate)?.value ?: error("Variable event construction frame")
                        require(amount in 0..16384 && amount % 8 == 0L)
                        write(
                            instruction.destination,
                            Frame(top() + if (instruction.operation == Operation.ADD) amount else -amount)
                        )
                    } else {
                        val left = value(instruction.destination)
                        val right = read(instruction.source)
                        write(
                            instruction.destination,
                            if (left is Scalar || right is Scalar) Scalar(position) else Unknown
                        )
                    }
                }

                Operation.SHL -> {
                    val input = value(instruction.destination) as? Scalar
                    val shift = (instruction.source as? Immediate)?.value
                    write(
                        instruction.destination, if (input != null && shift != null && shift in 0..12)
                            scaled(input, 1L shl shift.toInt()) else Unknown
                    )
                }

                Operation.MULTIPLY_IMMEDIATE -> {
                    val input = read(instruction.source) as? Scalar
                    val factor = instruction.immediate
                    write(
                        instruction.destination, if (input != null && factor != null && factor in 1..4096)
                            scaled(input, factor) else Unknown
                    )
                }

                Operation.CMOV -> {
                    val left = value(instruction.destination)
                    val right = read(instruction.source)
                    write(
                        instruction.destination, if (left == right) left
                        else if (left is Scalar || right is Scalar) Scalar(position) else Unknown
                    )
                }

                Operation.CALL -> {
                    require(
                        (top() + 8) % 16 == 0L && listOf(
                            0,
                            1,
                            2,
                            6,
                            7,
                            8,
                            9,
                            10,
                            11
                        ).none { registers[it] is Frame }) {
                        "Event construction exposes a local frame or has an unaligned call"
                    }
                    state.stores.clear()
                    for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)) registers[register] = Unknown
                }

                Operation.CMP, Operation.TEST, Operation.NOP, Operation.ENDBR, Operation.JCC, Operation.JMP -> Unit
                Operation.RET -> {
                    require(top() == 0L)
                    val result =
                        registers[0] as? Element ?: error("Event constructor does not return a derived array element")
                    require(result.offset == 0L && result.stride in 16..4096 && result.stride % 8 == 0L)
                    fun field(argument: Int, width: Int) = state.stores.singleOrNull {
                        it.element.copy(offset = 0) == result && it.field == Field(argument, width) && it.width == width
                    }?.element?.offset ?: error("Event header has no unambiguous surviving typed argument store")

                    val type = field(2, 4)
                    val time = field(6, 8)
                    require(type + 4 <= time || time + 8 <= type)
                    results += EventHeader(result.stride.toInt(), type, time)
                }

                Operation.XOR, Operation.VECTOR_XOR, Operation.MOVSX, Operation.INC, Operation.DEC ->
                    write(instruction.destination, Unknown)

                else -> error("Unsupported event header operation: ${instruction.operation}")
            }
            val next = flow.successors.getValue(position)
            require(next.isNotEmpty() || instruction.operation == Operation.RET) { "Event construction leaves its function" }
            for (target in next) {
                val old = incoming[target]
                incoming[target] = if (old == null) state.copyState() else {
                    require(old.registers[4] == registers[4])
                    State(old.registers.mapIndexed { index, previous ->
                        if (previous == registers[index]) previous else Unknown
                    }.toMutableList(), old.stores.intersect(state.stores).toMutableSet())
                }
            }
        }
        return results.distinct().singleOrNull() ?: error("Event construction returns incompatible header layouts")
    }
}
