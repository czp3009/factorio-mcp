package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Pointer provenance in private constructor locals, including loops and bounded native reference borrows.
 * This does not authorize dereferencing returned expressions or establish the ABI of a borrowed object.
 * All escaped ranges remain invalidated by subsequent calls and external writes, even after a borrow returns.
 */
internal class ConstructorValues(
    private val flow: X64ControlFlow,
    private val borrows: Map<Long, List<Borrow>>,
) {
    data class Borrow(val register: Int, val extent: Int)
    sealed interface Value
    data class Argument(val register: Int, val adjustment: Long = 0) : Value
    data class Load(val base: Value, val member: Long, val site: Long) : Value
    data class Adjusted(val base: Value, val amount: Long) : Value
    data class Nullable(val base: Value, val adjustment: Long) : Value
    data class Constant(val value: Long) : Value
    private data class Frame(val offset: Long) : Value
    private data object Unknown : Value
    private data class Span(val start: Long, val size: Int) {
        fun overlaps(other: Span) = start < other.start + other.size && other.start < start + size
    }

    private data class State(
        val registers: MutableList<Value>,
        val locals: MutableMap<Span, Value>,
        val exposed: MutableSet<Span>,
        var tested: Value? = null,
    ) {
        fun copyState() = State(registers.toMutableList(), locals.toMutableMap(), exposed.toMutableSet(), tested)
    }

    private val frame = SysVLocalArgument(flow)
    private val before = mutableMapOf<Long, State>()

    init {
        require(borrows.all { (site, arguments) ->
            flow.body[site]?.operation == Operation.CALL &&
                    arguments.isNotEmpty() && arguments.map { it.register }.distinct().size == arguments.size &&
                    arguments.all { it.register in listOf(7, 6, 2, 1, 8, 9) && it.extent in 1..4096 }
        })
        val initial = MutableList<Value>(32) { Unknown }
        for (argument in listOf(7, 6, 2, 1, 8, 9)) initial[argument] = Argument(argument)
        initial[4] = Frame(0)
        before[0] = State(initial, mutableMapOf(), mutableSetOf())
        val pending = ArrayDeque<Long>()
        pending.add(0)
        var steps = 0
        while (pending.isNotEmpty()) {
            require(++steps <= 262144) { "Constructor provenance exceeds analysis bound" }
            val position = pending.removeFirst()
            val instruction = flow.body.getValue(position)
            val state = before.getValue(position).copyState()
            val registers = state.registers
            fun slot(memory: Memory): Span? = frame.address(position, memory)?.let {
                require(it >= checkNotNull(frame.registers(position)[4]) && it <= -memory.width)
                Span(it, memory.width)
            }

            fun adjust(value: Value, amount: Long): Value {
                if (amount !in -16384..16384) {
                    require(value !is Frame) { "Constructor performs unbounded local pointer arithmetic" }
                    return Unknown
                }
                return when (value) {
                    is Frame -> Frame(value.offset + amount)
                    is Argument -> value.copy(adjustment = value.adjustment + amount)
                    is Constant -> Constant(value.value + amount)
                    is Load -> Adjusted(value, amount)
                    is Adjusted -> value.copy(amount = value.amount + amount)
                    else -> Unknown
                }
            }

            fun read(operand: X64Instructions.Operand?): Value = when (operand) {
                is Immediate -> Constant(operand.value)
                is Register -> registers[operand.number].let { value ->
                    require(operand.width == 8 || value !is Frame) { "Constructor truncates a local pointer" }
                    if (operand.width == 8) value else if (value is Constant && operand.width in listOf(1, 2, 4))
                        Constant(value.value and ((1L shl (operand.width * 8)) - 1)) else Unknown
                }

                is Memory -> {
                    val local = slot(operand)
                    if (local != null) state.locals[local] ?: Unknown
                    else if (operand.width == 8 && !operand.relative && operand.index == null) {
                        val base = operand.base?.let { registers[it] }
                        if (base is Argument || base is Load) {
                            var depth = 0
                            var parent = base
                            while (parent is Load) {
                                ++depth
                                parent = parent.base
                            }
                            if (depth < 4) Load(base, operand.displacement, position) else Unknown
                        } else Unknown
                    } else Unknown
                }

                else -> Unknown
            }

            fun invalidateExposed() {
                state.locals.keys.removeAll { local -> state.exposed.any(local::overlaps) }
            }

            fun write(operand: X64Instructions.Operand?, value: Value) {
                when (operand) {
                    is Register -> {
                        require(operand.width == 8 || value !is Frame && registers[operand.number] !is Frame) {
                            "Constructor loses a local pointer through a partial register write"
                        }
                        registers[operand.number] = if (operand.width == 8) value
                        else if (operand.width == 4 && value is Constant) Constant(value.value and 0xffffffffL)
                        else Unknown
                    }

                    is Memory -> {
                        val local = slot(operand)
                        if (local == null) {
                            require(value !is Frame) { "Constructor exposes unbounded local storage" }
                            invalidateExposed()
                        } else {
                            require(value !is Frame) { "Constructor saves a local alias outside register tracking" }
                            state.locals.keys.removeAll(local::overlaps)
                            if (operand.width == 8) state.locals[local] = value
                        }
                    }

                    else -> error("Unsupported constructor destination")
                }
            }
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR, Operation.JMP, Operation.JCC, Operation.RET -> Unit
                Operation.PUSH, Operation.POP -> {
                    if (instruction.operation == Operation.PUSH) require(read(instruction.destination) !is Frame) {
                        "Constructor saves a frame alias through push"
                    }
                    if (instruction.operation == Operation.POP) write(instruction.destination, Unknown)
                    val top = checkNotNull(frame.registers(position)[4])
                    registers[4] = Frame(top + if (instruction.operation == Operation.PUSH) -8 else 8)
                }

                Operation.MOV, Operation.MOVZX, Operation.MOVSX, Operation.SCALAR_MOV, Operation.VECTOR_MOV ->
                    write(instruction.destination, read(instruction.source))

                Operation.LEA -> {
                    val memory = instruction.source as? Memory ?: error("Invalid constructor address")
                    require(memory.index == null || memory.base?.let { registers[it] } !is Frame &&
                            registers[memory.index] !is Frame) { "Constructor indexes a local pointer" }
                    val value = if (!memory.relative && memory.index == null)
                        memory.base?.let { adjust(registers[it], memory.displacement) } ?: Unknown else Unknown
                    write(instruction.destination, value)
                }

                Operation.TEST -> state.tested = if (instruction.destination == instruction.source)
                    read(instruction.source).takeIf { it != Unknown } else null

                Operation.CMP, Operation.SCALAR_COMPARE -> state.tested = null
                Operation.CMOV -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    val adjusted = if (instruction.condition == 4) left as? Adjusted else right as? Adjusted
                    val original = if (instruction.condition == 4) right else left
                    val value = if (instruction.condition in listOf(4, 5) && adjusted != null &&
                        adjusted.base == original && state.tested == original
                    )
                        Nullable(original, adjusted.amount)
                    else if (left == right) left else Unknown
                    require(value != Unknown || left !is Frame && right !is Frame)
                    write(instruction.destination, value)
                }

                Operation.CALL -> {
                    val ranges = borrows[position].orEmpty().map { borrow ->
                        Span(frame.argument(position, borrow.register, borrow.extent), borrow.extent)
                    }
                    // Include incidental volatile frame values conservatively. A known reference's full extent
                    // bounds them; a live register alone never supplies a guessed pointee extent.
                    for (number in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) {
                        val local = registers[number] as? Frame ?: continue
                        require(ranges.any { local.offset >= it.start && local.offset < it.start + it.size }) {
                            "Constructor call has an unbounded frame reference"
                        }
                    }
                    state.exposed += ranges
                    invalidateExposed()
                    for (number in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)) registers[number] = Unknown
                    state.tested = null
                }

                Operation.ADD, Operation.SUB -> {
                    val amount = (instruction.source as? Immediate)?.value
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    require(amount != null || left !is Frame && right !is Frame)
                    require(left !is Frame || amount != null && amount in -16384..16384) {
                        "Constructor performs unbounded local pointer arithmetic"
                    }
                    write(
                        instruction.destination, if (amount != null && amount in -16384..16384)
                            adjust(left, if (instruction.operation == Operation.ADD) amount else -amount) else Unknown
                    )
                    state.tested = null
                }

                Operation.XCHG -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    write(instruction.destination, right)
                    write(instruction.source, left)
                }

                else -> {
                    require(read(instruction.destination) !is Frame && read(instruction.source) !is Frame)
                    val zero = instruction.operation in listOf(Operation.XOR, Operation.VECTOR_XOR) &&
                            instruction.destination == instruction.source
                    write(instruction.destination, if (zero) Constant(0) else Unknown)
                    state.tested = null
                }
            }
            for (next in flow.successors.getValue(position)) {
                val old = before[next]
                fun merge(left: Value, right: Value): Value {
                    if (left == right) return left
                    // Losing a local alias could make a later call appear to have no frame arguments.
                    require(left !is Frame && right !is Frame) { "Constructor join loses a frame reference" }
                    return Unknown
                }

                val merged = if (old == null) state.copyState() else State(
                    old.registers.mapIndexed { index, value -> merge(value, registers[index]) }.toMutableList(),
                    old.locals.filter { (key, value) -> value == state.locals[key] }.toMutableMap(),
                    (old.exposed + state.exposed).toMutableSet(), old.tested.takeIf { it == state.tested },
                )
                if (old != merged) {
                    before[next] = merged
                    pending.add(next)
                }
            }
        }
    }

    fun register(site: Long, number: Int): Value? {
        require(site in flow.reachable && number in 0..31)
        return before.getValue(site).registers[number].takeUnless { it == Unknown || it is Frame }
    }

    fun field(call: Long, extent: Int, offset: Long, argument: Int = 6): Value? {
        require(offset in 0..extent - 8L)
        val start = frame.argument(call, argument, extent)
        return before.getValue(call).locals[Span(start + offset, 8)].takeUnless { it == Unknown }
    }

}
