package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Pointer provenance in private constructor locals, including loops and bounded native reference
 * borrows. This does not authorize dereferencing returned expressions or establish the ABI of a
 * borrowed object. All escaped ranges remain invalidated by subsequent calls and external writes,
 * even after a borrow returns.
 */
internal class ConstructorValues(
    private val flow: X64ControlFlow,
    private val borrows: Map<Long, List<Borrow>>,
    // An opt-in association witness. The caller proves these are valid original scalar arguments.
    private val scalarWitness: Map<Int, Long>? = null,
    // Each result must be independently proven to be an allocation of this bounded extent.
    private val allocations: Map<Long, Allocation> = emptyMap(),
    // Each selected set must independently describe that call's optimized input ABI.
    private val callInputs: Map<Long, Set<Int>> = emptyMap(),
    private val trackFrameAliases: Boolean = false,
) {
    private val localAliases = scalarWitness != null || trackFrameAliases

    // Every alias requires an independent proof of its pointer field and complete pointee bytes.
    // It authorizes only this call's pre-call aggregate; the borrowed storage is still forgotten.
    data class InlineAlias(val pointer: Int, val target: Int, val extent: Int)

    data class Borrow(
        val register: Int,
        val extent: Int,
        val aliases: List<InlineAlias> = emptyList(),
    )

    sealed interface Value

    data class Argument(val register: Int, val adjustment: Long = 0) : Value

    data class Load(val base: Value, val member: Long, val site: Long) : Value

    data class Adjusted(val base: Value, val amount: Long) : Value

    data class Nullable(val base: Value, val adjustment: Long) : Value

    data class Constant(val value: Long) : Value

    data class Allocation(val call: Long, val size: Long, val adjustment: Long = 0) : Value

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
        var equal: Boolean? = null,
    ) {
        fun copyState() =
            State(
                registers.toMutableList(),
                locals.toMutableMap(),
                exposed.toMutableSet(),
                tested,
                equal,
            )
    }

    private val frame = SysVLocalArgument(flow)
    private val before = mutableMapOf<Long, State>()
    private val frameUses = mutableMapOf<Pair<Long, Int>, Boolean>()

    /**
     * A differing alias may be forgotten only if no path reads it before a complete replacement.
     */
    private fun usesFrame(start: Long, register: Int): Boolean =
        frameUses.getOrPut(start to register) {
            val pending = ArrayDeque<Long>()
            pending.add(start)
            val visited = mutableSetOf<Long>()
            while (pending.isNotEmpty()) {
                val site = pending.removeFirst()
                if (!visited.add(site)) continue
                require(visited.size <= 8192) { "Frame alias liveness exceeds its bound" }
                val instruction = flow.body.getValue(site)
                if (instruction.operation == Operation.CALL) {
                    if (register in (callInputs[site] ?: setOf(0, 1, 2, 6, 7, 8, 9, 10, 11)))
                        return@getOrPut true
                    if (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)) continue
                } else {
                    val destination = instruction.destination as? Register
                    val replaced =
                        destination?.number == register &&
                            destination.width in listOf(4, 8) &&
                            destination.number in 0..15 &&
                            instruction.operation == Operation.XOR &&
                            instruction.source == destination
                    if (replaced) continue
                    fun reads(operand: Operand?): Boolean =
                        when (operand) {
                            is Register -> operand.number == register
                            is Memory -> operand.base == register || operand.index == register
                            else -> false
                        }
                    if (reads(instruction.source) || reads(instruction.destination as? Memory))
                        return@getOrPut true
                    if (destination?.number == register) {
                        if (
                            instruction.operation in
                                setOf(
                                    Operation.MOV,
                                    Operation.MOVZX,
                                    Operation.MOVSX,
                                    Operation.LEA,
                                    Operation.POP,
                                    Operation.SET,
                                )
                        ) {
                            if (destination.width in listOf(4, 8) && destination.number in 0..15)
                                continue
                        } else return@getOrPut true
                    }
                }
                pending.addAll(flow.successors.getValue(site))
            }
            false
        }

    private fun localArgument(
        site: Long,
        register: Int,
        extent: Int,
        registers: List<Value>,
    ): Long {
        if (!localAliases) return frame.argument(site, register, extent)
        require(
            flow.body[site]?.operation == Operation.CALL &&
                register in listOf(7, 6, 2, 1, 8, 9) &&
                extent in 1..4096
        )
        val pointer =
            (registers[register] as? Frame)?.offset
                ?: error("Native borrow loses its exact private frame alias")
        val stack =
            (registers[4] as? Frame)?.offset ?: error("Native borrow loses its stack pointer")
        require(
            stack == frame.registers(site)[4] &&
                (stack + 8) % 16 == 0L &&
                pointer >= stack &&
                pointer <= -extent
        ) {
            "Native borrow exceeds its verified private frame"
        }
        return pointer
    }

    init {
        require(
            callInputs.all { (site, inputs) ->
                flow.body[site]?.operation == Operation.CALL &&
                    inputs.all { it in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) } &&
                    borrows[site].orEmpty().all { it.register in inputs }
            }
        ) {
            "Call input witness does not match its native call and borrows"
        }
        require(
            allocations.all { (site, value) ->
                flow.body[site]?.operation == Operation.CALL &&
                    value.call == site &&
                    value.size in 1..(16 * 1024 * 1024) &&
                    value.adjustment == 0L
            }
        ) {
            "Allocation witness has no bounded native call result"
        }
        require(
            borrows.all { (site, arguments) ->
                flow.body[site]?.operation == Operation.CALL &&
                    arguments.isNotEmpty() &&
                    arguments.map { it.register }.distinct().size == arguments.size &&
                    arguments.all {
                        it.register in listOf(7, 6, 2, 1, 8, 9) &&
                            it.extent in 1..4096 &&
                            it.aliases.size <= 16 &&
                            it.aliases.map(InlineAlias::pointer).distinct().size ==
                                it.aliases.size &&
                            it.aliases.all { alias ->
                                localAliases &&
                                    alias.pointer in 0..it.extent - 8 &&
                                    alias.pointer % 8 == 0 &&
                                    alias.extent in 1..it.extent &&
                                    alias.target in 0..it.extent - alias.extent
                            }
                    }
            }
        )
        val initial = MutableList<Value>(32) { Unknown }
        for (argument in listOf(7, 6, 2, 1, 8, 9)) initial[argument] = Argument(argument)
        require(
            scalarWitness == null ||
                scalarWitness.isNotEmpty() &&
                    scalarWitness.keys.all { it in listOf(7, 6, 2, 1, 8, 9) }
        )
        scalarWitness?.forEach { (register, value) -> initial[register] = Constant(value) }
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
            fun slot(memory: Memory): Span? =
                (if (!localAliases) frame.address(position, memory)
                    else {
                        (memory.base?.let { registers[it] } as? Frame)?.let { base ->
                            require(
                                !memory.relative &&
                                    memory.index == null &&
                                    memory.displacement in -16384..16384
                            ) {
                                "Witness indexes or escapes a private frame alias"
                            }
                            base.offset + memory.displacement
                        }
                    })
                    ?.let {
                        require(
                            it >= checkNotNull(frame.registers(position)[4]) && it <= -memory.width
                        )
                        Span(it, memory.width)
                    }

            fun adjust(value: Value, amount: Long): Value {
                if (amount !in -16384..16384) {
                    require(value !is Frame) {
                        "Constructor performs unbounded local pointer arithmetic"
                    }
                    return Unknown
                }
                return when (value) {
                    is Frame -> Frame(value.offset + amount)
                    is Argument -> value.copy(adjustment = value.adjustment + amount)
                    is Allocation ->
                        (value.adjustment + amount)
                            .takeIf { it in 0..value.size }
                            ?.let { value.copy(adjustment = it) } ?: Unknown
                    is Constant -> Constant(value.value + amount)
                    is Load -> Adjusted(value, amount)
                    is Adjusted -> value.copy(amount = value.amount + amount)
                    else -> Unknown
                }
            }

            fun read(operand: X64Instructions.Operand?): Value =
                when (operand) {
                    is Immediate -> Constant(operand.value)
                    is Register ->
                        registers[operand.number].let { value ->
                            require(operand.width == 8 || value !is Frame) {
                                "Constructor truncates a local pointer"
                            }
                            if (operand.width == 8) value
                            else if (value is Constant && operand.width in listOf(1, 2, 4))
                                Constant(value.value and ((1L shl (operand.width * 8)) - 1))
                            else Unknown
                        }

                    is Memory -> {
                        val local = slot(operand)
                        if (local != null) {
                            if (localAliases)
                                require(
                                    state.locals.none { (span, value) ->
                                        value is Frame && span != local && span.overlaps(local)
                                    }
                                ) {
                                    "Witness reads only part of a saved frame alias"
                                }
                            state.locals[local]
                                ?: if (localAliases) {
                                    state.locals.entries
                                        .singleOrNull { (span, value) ->
                                            value is Constant &&
                                                span.size <= 8 &&
                                                local.size <= 8 &&
                                                local.start >= span.start &&
                                                local.start + local.size <= span.start + span.size
                                        }
                                        ?.let { (span, value) ->
                                            val shifted =
                                                (value as Constant).value ushr
                                                    ((local.start - span.start) * 8).toInt()
                                            Constant(
                                                if (local.size == 8) shifted
                                                else shifted and ((1L shl (local.size * 8)) - 1)
                                            )
                                        } ?: Unknown
                                } else Unknown
                        } else if (
                            operand.width == 8 && !operand.relative && operand.index == null
                        ) {
                            val base = operand.base?.let { registers[it] }
                            if (base is Argument || base is Load) {
                                var depth = 0
                                var parent = base
                                while (parent is Load) {
                                    ++depth
                                    parent = parent.base
                                }
                                if (depth < 4) Load(base, operand.displacement, position)
                                else Unknown
                            } else Unknown
                        } else Unknown
                    }

                    else -> Unknown
                }

            fun invalidateExposed(aliases: Map<Span, Frame> = emptyMap()) {
                if (localAliases)
                    require(
                        state.locals.none { (span, value) ->
                            value is Frame &&
                                state.exposed.any(span::overlaps) &&
                                aliases[span] != value
                        }
                    ) {
                        "Witness native borrow may overwrite or publish a saved frame alias"
                    }
                state.locals.keys.removeAll { local -> state.exposed.any(local::overlaps) }
            }

            fun write(operand: X64Instructions.Operand?, value: Value) {
                when (operand) {
                    is Register -> {
                        require(
                            operand.width == 8 ||
                                value !is Frame &&
                                    (operand.width == 4 && operand.number in 0..15 ||
                                        registers[operand.number] !is Frame)
                        ) {
                            "Constructor loses a local pointer through a partial register write"
                        }
                        registers[operand.number] =
                            if (operand.width == 8) value
                            else if (operand.width == 4 && value is Constant)
                                Constant(value.value and 0xffffffffL)
                            else Unknown
                    }

                    is Memory -> {
                        val local = slot(operand)
                        if (local == null) {
                            require(value !is Frame) {
                                "Constructor exposes unbounded local storage"
                            }
                            invalidateExposed()
                        } else {
                            require(value !is Frame || localAliases && operand.width == 8) {
                                "Constructor saves a local alias outside register tracking"
                            }
                            if (localAliases)
                                require(
                                    state.locals.none { (span, saved) ->
                                        saved is Frame &&
                                            span.overlaps(local) &&
                                            (local.start > span.start ||
                                                local.start + local.size < span.start + span.size)
                                    }
                                ) {
                                    "Witness overwrites only part of a saved frame alias"
                                }
                            state.locals.keys.removeAll(local::overlaps)
                            if (
                                operand.width == 8 ||
                                    localAliases &&
                                        value is Constant &&
                                        operand.width in listOf(1, 2, 4)
                            )
                                state.locals[local] = value
                        }
                    }

                    else -> error("Unsupported constructor destination")
                }
            }
            when (instruction.operation) {
                Operation.NOP,
                Operation.ENDBR,
                Operation.JMP,
                Operation.JCC,
                Operation.RET -> Unit
                Operation.PUSH,
                Operation.POP -> {
                    if (instruction.operation == Operation.PUSH)
                        require(read(instruction.destination) !is Frame) {
                            "Constructor saves a frame alias through push"
                        }
                    if (instruction.operation == Operation.POP)
                        write(instruction.destination, Unknown)
                    val top = checkNotNull(frame.registers(position)[4])
                    registers[4] =
                        Frame(top + if (instruction.operation == Operation.PUSH) -8 else 8)
                }

                Operation.MOV,
                Operation.MOVZX,
                Operation.MOVSX,
                Operation.SCALAR_MOV,
                Operation.VECTOR_MOV -> write(instruction.destination, read(instruction.source))

                Operation.LEA -> {
                    val memory =
                        instruction.source as? Memory ?: error("Invalid constructor address")
                    require(
                        memory.index == null ||
                            memory.base?.let { registers[it] } !is Frame &&
                                registers[memory.index] !is Frame
                    ) {
                        "Constructor indexes a local pointer"
                    }
                    val value =
                        if (!memory.relative && memory.index == null)
                            memory.base?.let { adjust(registers[it], memory.displacement) }
                                ?: Unknown
                        else Unknown
                    write(instruction.destination, value)
                }

                Operation.TEST -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    state.tested =
                        if (instruction.destination == instruction.source)
                            right.takeIf { it != Unknown }
                        else null
                    state.equal =
                        if (scalarWitness == null) null
                        else
                            when {
                                left is Constant && right is Constant ->
                                    (left.value and right.value) == 0L
                                left is Frame && left == right -> false
                                else -> null
                            }
                }

                Operation.CMP,
                Operation.SCALAR_COMPARE -> {
                    state.tested = null
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    val width =
                        when (val target = instruction.destination) {
                            is Register -> target.width
                            is Memory -> target.width
                            else -> 0
                        }
                    state.equal =
                        if (!localAliases || instruction.operation != Operation.CMP) null
                        else
                            when {
                                left is Frame && right is Frame -> left == right
                                left is Argument &&
                                    right is Argument &&
                                    left.register == right.register -> left == right
                                left is Constant &&
                                    right is Constant &&
                                    width in listOf(1, 2, 4, 8) -> {
                                    val mask = if (width == 8) -1L else (1L shl (width * 8)) - 1
                                    (left.value and mask) == (right.value and mask)
                                }
                                else -> null
                            }
                }
                Operation.CMOV -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    val adjusted =
                        if (instruction.condition == 4) left as? Adjusted else right as? Adjusted
                    val original = if (instruction.condition == 4) right else left
                    val value =
                        if (instruction.condition in listOf(4, 5) && state.equal != null)
                            if (state.equal == (instruction.condition == 4)) right else left
                        else if (
                            instruction.condition in listOf(4, 5) &&
                                adjusted != null &&
                                adjusted.base == original &&
                                state.tested == original
                        )
                            Nullable(original, adjusted.amount)
                        else if (left == right) left else Unknown
                    require(value != Unknown || left !is Frame && right !is Frame)
                    write(instruction.destination, value)
                }

                Operation.CALL -> {
                    val aliases = mutableMapOf<Span, Frame>()
                    val ranges =
                        borrows[position].orEmpty().map { borrow ->
                            val start =
                                localArgument(position, borrow.register, borrow.extent, registers)
                            for (alias in borrow.aliases) {
                                val slot = Span(start + alias.pointer, 8)
                                val target = Frame(start + alias.target)
                                require(state.locals[slot] == target) {
                                    "Native borrow lost its independently bounded inline alias"
                                }
                                val prior = aliases.put(slot, target)
                                require(prior == null || prior == target)
                            }
                            Span(start, borrow.extent)
                        }
                    // Include incidental volatile frame values conservatively. A known reference's
                    // full extent
                    // bounds them; a live register alone never supplies a guessed pointee extent.
                    for (number in callInputs[position] ?: listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) {
                        val local = registers[number] as? Frame ?: continue
                        require(
                            ranges.any {
                                local.offset >= it.start && local.offset < it.start + it.size
                            }
                        ) {
                            "Constructor call at $position has an unbounded frame reference in register $number at ${local.offset}"
                        }
                    }
                    state.exposed += ranges
                    invalidateExposed(aliases)
                    for (number in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)) registers[
                        number] = Unknown
                    allocations[position]?.let { registers[0] = it }
                    state.tested = null
                    state.equal = null
                }

                Operation.ADD,
                Operation.SUB -> {
                    val amount = (instruction.source as? Immediate)?.value
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    require(amount != null || left !is Frame && right !is Frame)
                    require(left !is Frame || amount != null && amount in -16384..16384) {
                        "Constructor performs unbounded local pointer arithmetic"
                    }
                    write(
                        instruction.destination,
                        if (amount != null && amount in -16384..16384)
                            adjust(
                                left,
                                if (instruction.operation == Operation.ADD) amount else -amount,
                            )
                        else Unknown,
                    )
                    state.tested = null
                    state.equal = null
                }

                Operation.XCHG -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    write(instruction.destination, right)
                    write(instruction.source, left)
                }

                Operation.ATOMIC_EXCHANGE_ADD -> {
                    // XADD replaces both operands. The source receives the old memory value, not
                    // its
                    // incoming pointer or constant; preserving it would invent provenance after
                    // refcount work.
                    require(instruction.destination is Memory && instruction.source is Register)
                    require(
                        read(instruction.destination) !is Frame &&
                            read(instruction.source) !is Frame
                    )
                    write(instruction.destination, Unknown)
                    write(instruction.source, Unknown)
                    state.tested = null
                    state.equal = null
                }

                else -> {
                    val zero =
                        instruction.operation in listOf(Operation.XOR, Operation.VECTOR_XOR) &&
                            instruction.destination == instruction.source
                    val cleared =
                        zero &&
                            (instruction.destination as? Register)?.let {
                                it.width == 4 && it.number in 0..15
                            } == true
                    require(
                        cleared ||
                            read(instruction.destination) !is Frame &&
                                read(instruction.source) !is Frame
                    )
                    write(instruction.destination, if (zero) Constant(0) else Unknown)
                    state.tested = null
                    state.equal = null
                }
            }
            val successors =
                flow.successors.getValue(position).filter { next ->
                    if (
                        instruction.operation != Operation.JCC ||
                            instruction.condition !in listOf(4, 5) ||
                            state.equal == null
                    )
                        true
                    else
                        next ==
                            if (state.equal == (instruction.condition == 4))
                                (instruction.destination as Immediate).value
                            else position + instruction.size
                }
            for (next in successors) {
                val outgoing =
                    if (flow.isCleanupEdge(position, next))
                        state.copyState().also { cleanup ->
                            // A successful allocation result never exists on exceptional transfer.
                            // Even when
                            // the cleanup shares a normal continuation, forgetting it is the
                            // conservative join.
                            for (number in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)) cleanup
                                .registers[number] = Unknown
                            cleanup.tested = null
                            cleanup.equal = null
                        }
                    else state
                val old = before[next]
                fun merge(left: Value, right: Value, register: Int): Value {
                    if (left == right) return left
                    // Losing a local alias could make a later call appear to have no frame
                    // arguments.
                    require(
                        left !is Frame && right !is Frame ||
                            trackFrameAliases && !usesFrame(next, register)
                    ) {
                        "Constructor join at $next loses a live frame reference in register $register"
                    }
                    return Unknown
                }

                if (old != null && localAliases)
                    for (key in old.locals.keys + outgoing.locals.keys) {
                        val left = old.locals[key]
                        val right = outgoing.locals[key]
                        require(left == right || left !is Frame && right !is Frame) {
                            "Witness join loses a saved frame alias"
                        }
                    }
                val merged =
                    if (old == null) outgoing.copyState()
                    else
                        State(
                            old.registers
                                .mapIndexed { index, value ->
                                    merge(value, outgoing.registers[index], index)
                                }
                                .toMutableList(),
                            old.locals
                                .filter { (key, value) -> value == outgoing.locals[key] }
                                .toMutableMap(),
                            (old.exposed + outgoing.exposed).toMutableSet(),
                            old.tested.takeIf { it == outgoing.tested },
                            old.equal.takeIf { it == outgoing.equal },
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
        return before[site]?.registers?.get(number)?.takeUnless { it == Unknown || it is Frame }
    }

    fun field(call: Long, extent: Int, offset: Long, argument: Int = 6): Value? {
        require(offset in 0..extent - 8L)
        val start = localArgument(call, argument, extent, before.getValue(call).registers)
        return before.getValue(call).locals[Span(start + offset, 8)].takeUnless { it == Unknown }
    }
}
