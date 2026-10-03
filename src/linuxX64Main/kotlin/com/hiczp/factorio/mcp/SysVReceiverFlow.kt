package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Bounded control-flow provenance. Nested pointer expressions are evidence, not authorized runtime reads. */
internal class SysVReceiverFlow(
    bytes: BinaryView,
    private val address: Long,
    private val receiverSize: Long,
    private val globals: Map<Long, Long> = emptyMap(),
    noReturnFunctions: Set<Long> = emptySet(),
    argument: ArgumentScalar? = null,
    // The caller must independently prove each selected condition and establish its runtime precondition.
    selectedEdges: Map<Long, Long> = emptyMap(),
    private val receiverRegister: Int = 7,
    // Each output must come from an independent callee/dispatch proof for this exact function body.
    private val frameOutputs: Map<Long, List<FrameOutput>> = emptyMap(),
    // Callers independently establish that these allocator calls return external storage, never a frame alias.
    private val heapResults: Set<Long> = emptySet(),
    // Independently bounded external indexed accesses in this exact body, never frame aliases.
    private val externalIndexedAccesses: Set<Long> = emptySet(),
) {
    init {
        require(bytes.size in 1..8192 && address >= 0 && address <= Long.MAX_VALUE - bytes.size && receiverSize > 0)
        require(globals.all { (address, size) -> address >= 0 && size >= 8 })
        require(noReturnFunctions.all { it >= 0 })
        require(receiverRegister in listOf(7, 6))
    }

    sealed interface Value
    data class Receiver(val adjustment: Long = 0) : Value
    data class Global(val address: Long, val loadedAt: Long) : Value
    data class Pointer(val base: Value, val offset: Long, val loadedAt: Long) : Value
    data object Heap : Value
    data object Unknown : Value
    data class Original(val number: Int) : Value
    data class Stack(val offset: Long) : Value
    data class Constant(val value: Long) : Value
    data class Converted(val offset: Long) : Value
    data class FrameOutput(val register: Int, val offset: Long, val size: Int) {
        init {
            require(register in listOf(7, 6, 2, 1, 8, 9) && size in 1..4096 && offset in -16384..-size.toLong())
        }
    }

    private data class State(val registers: MutableList<Value>, val stack: MutableMap<Pair<Long, Int>, Value>) {
        fun copyState() = State(registers.toMutableList(), stack.toMutableMap())
    }

    val instructions = X64Instructions(bytes).all(2048)
    private val body = instructions.associateBy { it.offset }
    private val entryBranch = argument?.let { SysVEntryBranch.resolve(bytes, it) }

    init {
        require(selectedEdges.all { (offset, target) ->
            val instruction = body[offset]
            instruction?.operation == Operation.JCC && target in body &&
                    (target == instruction.offset + instruction.size || target == (instruction.destination as? Immediate)?.value)
        }) { "Selected condition is not a decoded branch edge" }
        require(entryBranch == null || entryBranch.branch !in selectedEdges)
        require(frameOutputs.all { (offset, outputs) ->
            body[offset]?.operation == Operation.CALL && outputs.isNotEmpty() &&
                    outputs.map { it.register }.distinct().size == outputs.size && outputs.all { first ->
                outputs.all { second ->
                    first == second || first.offset + first.size <= second.offset ||
                            second.offset + second.size <= first.offset
                }
            }
        }) { "Output proof does not identify distinct call arguments" }
        require(heapResults.all { body[it]?.operation == Operation.CALL })
        require(externalIndexedAccesses.all { offset ->
            body[offset]?.let {
                listOfNotNull(it.destination as? Memory, it.source as? Memory).any { memory ->
                    !memory.relative && memory.index != null
                }
            } == true
        })
    }

    private val successors = instructions.associate { instruction ->
        val next = instruction.offset + instruction.size
        val branch = if (instruction.operation in setOf(Operation.JMP, Operation.JCC)) {
            (instruction.destination as? Immediate)?.value ?: error("Indirect destructor control flow")
        } else null
        val destinations = when (instruction.operation) {
            Operation.RET -> emptyList()
            Operation.CALL -> if ((instruction.destination as? Immediate)?.value?.let { relative ->
                    relative >= -address && relative <= Long.MAX_VALUE - address && address + relative in noReturnFunctions
                } == true) emptyList() else listOf(next)

            Operation.JMP -> listOf(checkNotNull(branch))
            Operation.JCC -> if (instruction.offset in selectedEdges) listOf(selectedEdges.getValue(instruction.offset))
            else if (entryBranch?.branch == instruction.offset) listOf(entryBranch.successor)
            else listOf(next, checkNotNull(branch))

            else -> listOf(next)
        }.filter { it in 0 until bytes.size }
        require(destinations.all { it in body }) { "Destructor branch enters an instruction" }
        instruction.offset to destinations
    }
    val reachable: Set<Long> = buildSet {
        val pending = ArrayDeque<Long>()
        pending.add(0)
        while (pending.isNotEmpty()) {
            val offset = pending.removeFirst()
            if (add(offset)) pending.addAll(successors.getValue(offset))
        }
    }
    val predecessors = mutableMapOf<Long, MutableSet<Long>>()

    /** Every selected entry path to target must pass this exact edge. */
    fun requiresEdge(target: Long, from: Long, to: Long): Boolean {
        require(target in reachable && to in successors.getValue(from))
        val pending = ArrayDeque<Long>()
        val visited = mutableSetOf<Long>()
        pending.add(0)
        while (pending.isNotEmpty()) {
            val position = pending.removeFirst()
            if (position == target) return false
            if (visited.add(position)) pending.addAll(
                successors.getValue(position).filterNot { position == from && it == to })
        }
        return true
    }

    init {
        for ((from, destinations) in successors) if (from in reachable) {
            for (to in destinations) predecessors.getOrPut(to) { mutableSetOf() } += from
        }
    }

    fun before(targetOffset: Long): List<Value> = analyzeBefore(targetOffset, discardBorrowedFrame = false)

    /**
     * Register-argument evidence only. Native dispatchers lend local lifetime sentinels to callbacks.
     * Forget every saved value on external writes or calls; never use this mode to prove
     * frame restoration, output storage, or the safety of dereferencing a derived memory expression.
     */
    fun dispatchArguments(targetOffset: Long): List<Value> {
        require(body.getValue(targetOffset).operation == Operation.CALL)
        return analyzeBefore(targetOffset, discardBorrowedFrame = true).also {
            validateCallFrame(targetOffset, it)
        }
    }

    /**
     * Register provenance at a call that borrows locals, without exposing any frame address as evidence.
     * The caller proves local argument bounds/ownership separately. This does not authorize the callee ABI,
     * native output writes, frame restoration or object lifetime.
     */
    fun borrowedCallValues(targetOffset: Long): List<Value> {
        require(body.getValue(targetOffset).operation == Operation.CALL)
        val values = analyzeBefore(targetOffset, discardBorrowedFrame = true)
        validateCallFrame(targetOffset, values, borrowedFrame = true)
        return values.map { if (it is Stack) Unknown else it }
    }

    private fun analyzeBefore(targetOffset: Long, discardBorrowedFrame: Boolean): List<Value> {
        require(targetOffset in reachable) { "Selected instruction has no normal entry path" }
        val needed = mutableSetOf<Long>()
        val reverse = ArrayDeque<Long>()
        reverse.add(targetOffset)
        while (reverse.isNotEmpty()) {
            val position = reverse.removeFirst()
            if (needed.add(position)) for (parent in predecessors[position].orEmpty()) reverse.add(parent)
        }
        require(0L in needed) { "Selected instruction is unreachable from entry" }
        val incoming = mutableMapOf<Long, State>()
        val pending = ArrayDeque<Long>()
        fun enqueue(position: Long, state: State) {
            if (position !in needed) return
            val old = incoming[position]
            if (old == null) {
                incoming[position] = state.copyState()
                pending.add(position)
            } else {
                require(old.registers[4] == state.registers[4]) { "Destructor branches disagree on stack depth" }
                fun merge(left: Value, right: Value): Value {
                    if (left == right) return left
                    require(discardBorrowedFrame || left !is Stack && right !is Stack) {
                        "Branch join loses a saved-frame pointer"
                    }
                    fun external(value: Value) =
                        value is Receiver || value is Pointer || value is Global || value == Heap
                    if (external(left) && external(right)) return Heap
                    return Unknown
                }

                val merged = State(old.registers.mapIndexed { index, value ->
                    merge(value, state.registers[index])
                }.toMutableList(), (old.stack.keys + state.stack.keys).associateWith { slot ->
                    merge(old.stack[slot] ?: Unknown, state.stack[slot] ?: Unknown)
                }.toMutableMap())
                if (merged != old) {
                    incoming[position] = merged
                    pending.add(position)
                }
            }
        }

        val initial = MutableList<Value>(32) { Original(it) }
        initial[receiverRegister] = Receiver()
        initial[4] = Stack(0)
        enqueue(0, State(initial, mutableMapOf()))
        val loopLoads = mutableMapOf<Pair<Long, Long>, Set<Long>>()
        fun repeatedLoads(from: Long, to: Long): Set<Long> = loopLoads.getOrPut(from to to) {
            val forward = mutableSetOf<Long>()
            val visit = ArrayDeque<Long>()
            visit.add(to)
            while (visit.isNotEmpty()) {
                val node = visit.removeFirst()
                if (node in needed && forward.add(node)) visit.addAll(successors.getValue(node))
            }
            val backward = mutableSetOf<Long>()
            visit.add(from)
            while (visit.isNotEmpty()) {
                val node = visit.removeFirst()
                if (node in forward && backward.add(node)) visit.addAll(predecessors[node].orEmpty())
            }
            backward
        }

        var steps = 0
        while (pending.isNotEmpty()) {
            require(++steps <= 32768) { "Destructor provenance exceeds analysis bound" }
            val position = pending.removeFirst()
            // A call reached again through a loop must include its own volatile-register effects.
            // For an acyclic target, its outgoing effects cannot contribute to the requested pre-state.
            if (position == targetOffset && successors.getValue(position).none { it in needed }) continue
            val state = incoming.getValue(position).copyState()
            val registers = state.registers
            val instruction = body.getValue(position)
            fun top() = (registers[4] as? Stack)?.offset ?: error("Unproven destructor frame")
            fun stackSlot(memory: Memory): Long? {
                val base = memory.base?.let { registers[it] } as? Stack ?: return null
                require(!memory.relative && memory.index == null)
                val offset = base.offset + memory.displacement
                require(offset >= top() && offset <= -memory.width) { "Destructor accesses outside its saved frame" }
                return offset
            }

            fun register(value: Register): Value {
                val original = registers[value.number]
                require(value.width == 8 || original !is Stack) { "Instruction truncates a saved-frame pointer" }
                return if (value.width == 8) original else if (original is Constant && value.width in listOf(1, 2, 4))
                    Constant(original.value and ((1L shl (value.width * 8)) - 1)) else Unknown
            }

            fun memory(value: Memory, reading: Boolean = false): Value {
                if (value.relative) {
                    require(value.base == null && value.index == null)
                    val next = address + position + instruction.size
                    require(value.displacement >= -next && value.displacement <= Long.MAX_VALUE - next)
                    return if (value.width == 8) Global(next + value.displacement, position) else Unknown
                }
                stackSlot(value)?.let { offset ->
                    if (reading) require(state.stack.none { (slot, saved) ->
                        saved is Stack && slot != (offset to value.width) &&
                                slot.first < offset + value.width && offset < slot.first + slot.second
                    }) { "Load loses a saved-frame pointer through an overlapping slot" }
                    return state.stack[offset to value.width] ?: Unknown
                }
                if (value.index != null) {
                    require(
                        position in externalIndexedAccesses && registers[value.index] !is Stack &&
                            value.base?.let { registers[it] is Stack } != true) {
                        "Indexed access lacks independent external bounds or aliases the frame"
                    }
                    return Unknown
                }
                val base = value.base?.let { registers[it] }
                when (base) {
                    is Receiver -> require(
                        value.displacement >= -base.adjustment &&
                                base.adjustment + value.displacement <= receiverSize - value.width
                    ) { "Destructor member exceeds receiver bounds" }

                    is Global -> globals[base.address]?.let { size ->
                        require(value.displacement in 0..size - value.width) { "Member exceeds global object bounds" }
                    }

                    is Pointer -> Unit
                    Heap -> Unit
                    else -> {
                        // A read through an unrelated argument establishes no provenance. It cannot change
                        // saved registers; writes through the same unknown base must still be rejected.
                        require(reading) { "Destructor memory base has unknown provenance" }
                        return Unknown
                    }
                }
                if (value.width != 8) return Unknown
                if (base == Heap) return Heap
                val provenBase = checkNotNull(base)
                var depth = 0
                var parent = provenBase
                while (parent is Pointer) {
                    if (++depth >= 8) return Unknown
                    parent = parent.base
                }
                return if (provenBase is Receiver) Pointer(
                    Receiver(),
                    provenBase.adjustment + value.displacement,
                    position
                )
                else Pointer(provenBase, value.displacement, position)
            }

            fun read(value: X64Instructions.Operand?): Value = when (value) {
                is Register -> register(value)
                is Immediate -> Constant(value.value)
                is Memory -> memory(value, reading = true)
                else -> Unknown
            }

            fun write(target: X64Instructions.Operand?, value: Value) {
                when (target) {
                    is Register -> {
                        if (target.number == 4) require(target.width == 8 && value is Stack && value.offset in -16384..0)
                        require(target.width == 8 || value !is Stack && registers[target.number] !is Stack) {
                            "Store truncates a saved-frame pointer"
                        }
                        registers[target.number] = if (target.width == 8) value
                        else if (target.width == 4 && value is Constant) Constant(value.value and 0xffffffffL) else Unknown
                    }

                    is Memory -> {
                        val slot = stackSlot(target)
                        if (slot != null) {
                            require(state.stack.none { (savedSlot, saved) ->
                                saved is Stack && savedSlot.first < slot + target.width && slot < savedSlot.first + savedSlot.second &&
                                        !(slot <= savedSlot.first && savedSlot.first + savedSlot.second <= slot + target.width)
                            }) { "Store partially overwrites a saved-frame pointer" }
                            state.stack.keys.removeAll { (start, width) -> start < slot + target.width && slot < start + width }
                            state.stack[slot to target.width] = value
                        } else {
                            memory(target) // Validate that this store cannot overwrite the saved frame.
                            // After a local sentinel escapes, an external link may alias that same frame.
                            if (discardBorrowedFrame) state.stack.clear()
                            else require(value !is Stack) { "Destructor exposes its saved frame" }
                        }
                    }

                    else -> error("Unsupported destructor destination")
                }
            }
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR, Operation.CMP, Operation.TEST, Operation.BIT_TEST, Operation.JMP, Operation.JCC -> Unit
                Operation.PUSH -> {
                    val value = read(instruction.destination)
                    val next = top() - 8
                    require(next >= -16384)
                    registers[4] = Stack(next)
                    state.stack.keys.removeAll { (start, width) -> start < next + 8 && next < start + width }
                    state.stack[next to 8] = value
                }

                Operation.POP -> {
                    val target = instruction.destination as? Register ?: error("Unsupported destructor pop")
                    require(target.width == 8 && target.number != 4)
                    val offset = top()
                    registers[target.number] = state.stack.remove(offset to 8) ?: Unknown
                    registers[4] = Stack(offset + 8)
                }

                Operation.MOV, Operation.MOVZX, Operation.SCALAR_MOV -> write(
                    instruction.destination,
                    read(instruction.source)
                )

                Operation.INT_TO_DOUBLE -> {
                    require(read(instruction.source) !is Stack) { "Conversion loses a saved-frame pointer" }
                    write(instruction.destination, Converted(position))
                }

                Operation.SET -> write(instruction.destination, Unknown)
                Operation.MOVSX -> {
                    require(read(instruction.source) !is Stack) { "Sign extension loses a saved-frame pointer" }
                    write(instruction.destination, Unknown)
                }

                Operation.LEA -> {
                    val target = instruction.destination as? Register ?: error("Unsupported destructor address target")
                    val source = instruction.source as? Memory ?: error("Unsupported destructor address")
                    if (target.width == 4) {
                        require(listOfNotNull(source.base, source.index).none { registers[it] is Stack }) {
                            "Scalar address calculation truncates a frame alias"
                        }
                        write(target, Unknown)
                        for (next in successors.getValue(position)) enqueue(next, state)
                        continue
                    }
                    require(target.width == 8 && source.index == null)
                    val value = if (source.relative) Heap else when (val base = source.base?.let { registers[it] }) {
                        is Receiver -> {
                            require(
                                source.displacement >= -base.adjustment &&
                                        source.displacement <= receiverSize - base.adjustment
                            )
                            Receiver(base.adjustment + source.displacement)
                        }

                        is Stack -> {
                            require(source.displacement >= top() - base.offset && source.displacement <= -base.offset)
                            Stack(base.offset + source.displacement)
                        }

                        is Global, is Pointer, Heap -> Heap
                        else -> Unknown
                    }
                    write(target, value)
                }

                Operation.ADD, Operation.SUB -> {
                    val target = instruction.destination
                    if (target == Register(4, 8)) {
                        val amount = (instruction.source as? Immediate)?.value ?: error("Variable destructor frame")
                        require(amount in 0..16384 && amount % 8 == 0L)
                        val next = top() + if (instruction.operation == Operation.ADD) amount else -amount
                        require(next in -16384..0)
                        registers[4] = Stack(next)
                        state.stack.keys.removeAll { (start, _) -> start < next }
                    } else {
                        val original = read(target)
                        require(original !is Stack && read(instruction.source) !is Stack) {
                            "Arithmetic loses a saved-frame pointer"
                        }
                        val amount = (instruction.source as? Immediate)?.value
                        val externalAdjustment = instruction.operation == Operation.ADD && target is Register &&
                                target.width == 8 && amount != null && amount in 0..4096 &&
                                (original is Pointer || original == Heap)
                        // As with LEA, an adjusted external pointer carries no exact member provenance.
                        write(target, if (externalAdjustment) Heap else Unknown)
                    }
                }

                Operation.XCHG -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    write(instruction.destination, right)
                    write(instruction.source, left)
                }

                Operation.XOR, Operation.AND, Operation.OR, Operation.INC, Operation.DEC,
                Operation.SHL, Operation.SHR, Operation.SAR -> {
                    require(read(instruction.destination) !is Stack && read(instruction.source) !is Stack) {
                        "Arithmetic loses a saved-frame pointer"
                    }
                    val zero = instruction.operation == Operation.XOR && instruction.destination is Register &&
                            instruction.destination == instruction.source
                    write(instruction.destination, if (zero) Constant(0) else Unknown)
                }

                Operation.CMOV -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    require(left == right || left !is Stack && right !is Stack) { "Conditional move loses a saved-frame pointer" }
                    fun external(value: Value) =
                        value is Receiver || value is Pointer || value is Global || value == Heap
                    write(
                        instruction.destination,
                        if (left == right) left else if (external(left) && external(right)) Heap else Unknown
                    )
                }

                Operation.VECTOR_XOR -> {
                    val target = instruction.destination as? Register ?: error("Unsupported vector destination")
                    require(target.number in 16..31 && target.width == 16 && instruction.source == target)
                    registers[target.number] = Constant(0)
                }

                Operation.DOUBLE_DIVIDE, Operation.DOUBLE_MULTIPLY -> {
                    val target = instruction.destination as? Register ?: error("Invalid floating-point destination")
                    require(target.number in 16..31 && target.width == 8)
                    val source = instruction.source
                    require(source is Register && source.number in 16..31 && source.width == 8 ||
                            source is Memory && source.width == 8)
                    require(read(source) !is Stack && registers[target.number] !is Stack) {
                        "Floating-point arithmetic loses a saved-frame pointer"
                    }
                    registers[target.number] = Unknown
                }

                Operation.VECTOR_MOV -> {
                    val source =
                        instruction.source as? Register ?: error("Vector loads cannot preserve pointer provenance")
                    require(source.number in 16..31 && source.width == 16 && registers[source.number] == Constant(0)) {
                        "Only proven vector zero stores preserve the frame proof"
                    }
                    when (val target = instruction.destination) {
                        is Register -> {
                            require(target.number in 16..31 && target.width == 16)
                            registers[target.number] = Constant(0)
                        }

                        is Memory -> write(target, Constant(0))
                        else -> error("Unsupported vector destination")
                    }
                }

                Operation.CALL -> {
                    validateCallFrame(position, registers, discardBorrowedFrame)
                    validateOutputStorage(position, state)
                    if (discardBorrowedFrame) state.stack.clear()
                    for (output in frameOutputs[position].orEmpty()) {
                        val affected = state.stack.filterKeys { (start, width) ->
                            start < output.offset + output.size && output.offset < start + width
                        }
                        state.stack.keys.removeAll(affected.keys)
                    }
                    for (index in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) registers[index] = Unknown
                    for (index in 16..31) registers[index] = Unknown
                    if (position in heapResults) registers[0] = Heap
                }

                else -> error("Unsupported instruction on receiver provenance path: ${instruction.operation}")
            }
            for (next in successors.getValue(position)) {
                if (next <= position) {
                    // The same load instruction in another iteration does not establish object identity.
                    // A pointer captured before the cycle remains the same saved value, even if its pointee
                    // changed. This is argument provenance only, never a cross-callback lifetime guarantee.
                    val repeated = repeatedLoads(position, next)
                    fun unstable(value: Value): Boolean = when (value) {
                        is Pointer -> value.loadedAt in repeated || unstable(value.base)
                        is Global -> value.loadedAt in repeated
                        else -> false
                    }

                    fun forget(value: Value): Value = if (unstable(value)) Heap else value
                    enqueue(
                        next, State(
                        state.registers.map(::forget).toMutableList(),
                        state.stack.mapValues { (_, value) -> forget(value) }.toMutableMap()
                    )
                    )
                } else enqueue(next, state)
            }
        }
        // This proves normal entry paths only; exceptional entries do not acquire an invented receiver state.
        require(incoming.keys.containsAll(needed)) { "Receiver proof has an unproven entry path" }
        val result = incoming.getValue(targetOffset)
        if (targetOffset in frameOutputs) {
            validateCallFrame(targetOffset, result.registers)
            validateOutputStorage(targetOffset, result)
        }
        return result.registers.toList()
    }

    fun call(targetOffset: Long): List<Value> {
        require(body.getValue(targetOffset).operation == Operation.CALL)
        return before(targetOffset).also { registers ->
            validateCallFrame(targetOffset, registers)
        }
    }

    private fun validateCallFrame(offset: Long, registers: List<Value>, borrowedFrame: Boolean = false) {
        val stack = registers[4] as? Stack ?: error("Unproven call frame")
        val outputs = frameOutputs[offset].orEmpty()
        require((8 + stack.offset) % 16 == 0L && outputs.all {
            registers[it.register] == Stack(it.offset) && it.offset >= stack.offset && it.offset <= -it.size
        } && (borrowedFrame || listOf(0, 1, 2, 6, 7, 8, 9, 10, 11).all { register ->
            registers[register] !is Stack || outputs.any { it.register == register }
        })) { "Call has an unproven frame or exposes unverified local storage" }
    }

    private fun validateOutputStorage(offset: Long, state: State) {
        for (output in frameOutputs[offset].orEmpty()) require(state.stack.filterKeys { (start, width) ->
            start < output.offset + output.size && output.offset < start + width
        }.values.none { it is Stack || it is Original && it.number in listOf(3, 5, 12, 13, 14, 15) }) {
            "Proven output overlaps a saved register or frame pointer"
        }
    }
}
