package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Proves a receiver-only, two-integer-word getter and its bounded parent-walk control flow. */
internal object SysVRectangleAbi {
    data class Proof(val parent: Long, val virtualSlots: Set<Int>)

    private sealed interface Value
    private data object Unknown : Value
    private data object NullPointer : Value
    private data object Scalar : Value
    private data object ObjectPointer : Value
    private data class Table(val receiver: Value) : Value
    private data class Original(val register: Int) : Value
    private data class Stack(val offset: Long) : Value
    private data class Member(val offset: Long, val width: Int, val base: Int) : Value
    private data class State(
        val registers: MutableList<Value>,
        val stack: MutableMap<Long, Value>,
        var tested: Member? = null,
        var flags: Boolean = false,
    ) {
        fun copyState() = State(registers.toMutableList(), stack.toMutableMap(), tested, flags)
    }

    fun resolve(image: ElfImage, function: ElfImage.Symbol, size: Long, table: ItaniumVtable): Proof {
        require(function.size in 1..4096)
        EhFrames(image).function(function)
        return analyze(image.functionBytes(function, 4096), size) { slot ->
            val method = table.function(image, slot)
            require(method.name.startsWith("_ZNK") && method.name.endsWith("Ev")) {
                "Rectangle getter dispatches a method with unsupported receiver/arguments"
            }
        }
    }

    fun analyze(bytes: BinaryView, objectSize: Long, validateSlot: (Int) -> Unit): Proof {
        require(bytes.size in 1..4096 && objectSize in 8..(16 * 1024 * 1024))
        val body = X64Instructions(bytes).all(2048).associateBy { it.offset }
        val incoming = mutableMapOf<Long, State>()
        val pending = ArrayDeque<Long>()
        val parents = mutableSetOf<Long>()
        val slots = mutableSetOf<Int>()
        val backParents = mutableSetOf<Long>()
        val guardedParents = mutableSetOf<Long>()
        val preserved = setOf(3, 5, 12, 13, 14, 15)
        val volatile = setOf(0, 1, 2, 6, 7, 8, 9, 10, 11)
        var returns = 0
        fun merge(a: Value, b: Value): Value = when {
            a == b -> a
            a is Table && b is Table -> Table(merge(a.receiver, b.receiver))
            a is Member && b is Member && a.offset == b.offset && a.width == b.width ->
                Member(a.offset, a.width, -1)

            (a == ObjectPointer && b is Member && b.width == 8) ||
                    (b == ObjectPointer && a is Member && a.width == 8) -> ObjectPointer

            (a == Scalar || a is Member) && (b == Scalar || b is Member) -> Scalar
            else -> Unknown
        }

        fun enqueue(offset: Long, value: State) {
            require(offset in body) { "Rectangle branch leaves the function or enters an instruction" }
            val old = incoming[offset]
            if (old == null) {
                incoming[offset] = value.copyState()
                pending.add(offset)
            } else {
                require(old.registers[4] == value.registers[4]) { "Rectangle control flow changes stack depth" }
                val merged =
                    State(
                        old.registers.mapIndexed { index, v -> merge(v, value.registers[index]) }.toMutableList(),
                        old.stack.mapValues { (key, v) -> merge(v, value.stack[key] ?: Unknown) }.toMutableMap(),
                        old.tested.takeIf { it == value.tested }, old.flags && value.flags
                    )
                if (merged != old) {
                    incoming[offset] = merged
                    pending.add(offset)
                }
            }
        }

        val initial = MutableList<Value>(16) { Original(it) }
        initial[7] = ObjectPointer
        initial[4] = Stack(0)
        enqueue(0, State(initial, mutableMapOf()))
        var steps = 0
        while (pending.isNotEmpty()) {
            require(++steps <= 16384) { "Rectangle ABI dataflow exceeds its bound" }
            val offset = pending.removeFirst()
            val state = incoming.getValue(offset).copyState()
            val instruction = body.getValue(offset)
            val registers = state.registers
            fun top() = (registers[4] as? Stack)?.offset ?: error("Rectangle stack provenance was lost")
            fun stackAddress(memory: Memory): Long {
                val base = registers.getOrNull(memory.base ?: -1) as? Stack
                    ?: error("Rectangle writes outside its stack frame")
                require(!memory.relative && memory.index == null && memory.width == 8)
                val address = base.offset + memory.displacement
                require(address >= top() && address + 8 <= 0) { "Rectangle stack access exceeds reserved storage" }
                return address
            }

            fun pointer(value: Value) {
                when (value) {
                    ObjectPointer -> Unit
                    is Member -> {
                        require(value.width == 8) { "Rectangle dereferences a truncated pointer" }
                        parents.add(value.offset)
                    }

                    else -> error("Rectangle reads through an unknown receiver at $offset: $value")
                }
            }

            fun read(operand: Operand?): Value = when (operand) {
                is Immediate -> Scalar
                is Register -> {
                    val value = registers[operand.number]
                    require(operand.width >= 4) { "Rectangle uses an unverified partial register" }
                    if (operand.width == 8) value else when (value) {
                        Scalar, is Member -> Scalar
                        else -> Unknown
                    }
                }

                is Memory -> {
                    require(!operand.relative && operand.index == null && operand.base != null)
                    val base = registers[operand.base]
                    if (base is Stack) state.stack[stackAddress(operand)] ?: Unknown
                    else {
                        pointer(base)
                        require(operand.displacement in 0..objectSize - operand.width)
                        if (operand.displacement == 0L && operand.width == 8) Table(base)
                        else Member(operand.displacement, operand.width, operand.base)
                    }
                }

                else -> error("Unsupported rectangle input")
            }

            fun known(value: Value) {
                require(value == Scalar || value == ObjectPointer || value is Member) {
                    "Rectangle consumes an uninitialized or additional input register"
                }
            }

            fun write(target: Operand?, value: Value) {
                when (target) {
                    is Register -> {
                        require(target.width >= 4)
                        registers[target.number] = if (target.width == 8) value else when (value) {
                            Scalar, is Member -> Scalar
                            else -> Unknown
                        }
                    }

                    is Memory -> state.stack[stackAddress(target)] = value
                    else -> error("Unsupported rectangle destination")
                }
            }

            var fallsThrough = true
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    val value = read(instruction.destination)
                    val next = top() - 8
                    require(next >= -4096)
                    registers[4] = Stack(next)
                    state.stack[next] = value
                }

                Operation.POP -> {
                    val destination = instruction.destination as? Register ?: error("Unsupported rectangle pop")
                    require(destination.width == 8 && destination.number != 4)
                    val previous = top()
                    require(previous < 0)
                    registers[destination.number] = state.stack[previous] ?: Unknown
                    registers[4] = Stack(previous + 8)
                }

                Operation.MOV -> write(instruction.destination, read(instruction.source))
                Operation.ADD, Operation.SUB -> {
                    if (instruction.destination == Register(4, 8)) {
                        val amount = (instruction.source as? Immediate)?.value ?: error("Variable rectangle frame")
                        require(amount in 0..4096 && amount % 8 == 0L)
                        val next = top() + if (instruction.operation == Operation.ADD) amount else -amount
                        require(next in -4096..0)
                        registers[4] = Stack(next)
                    } else {
                        known(read(instruction.destination))
                        known(read(instruction.source))
                        write(instruction.destination, Scalar)
                    }
                    state.flags = true
                    state.tested = null
                }

                Operation.XOR, Operation.OR, Operation.AND, Operation.SHL, Operation.SHR, Operation.SAR -> {
                    if (instruction.operation != Operation.XOR || instruction.destination != instruction.source)
                        known(read(instruction.destination))
                    if (instruction.operation != Operation.XOR || instruction.destination != instruction.source)
                        known(read(instruction.source))
                    write(instruction.destination, Scalar)
                    state.flags = true
                    state.tested = null
                }

                Operation.TEST, Operation.CMP -> {
                    val left = read(instruction.destination)
                    known(left)
                    known(read(instruction.source))
                    state.flags = true
                    state.tested = (left as? Member)?.takeIf {
                        instruction.operation == Operation.TEST &&
                                instruction.destination == instruction.source && it.width == 8
                    }
                }

                Operation.CALL -> {
                    val target = instruction.destination as? Memory ?: error("Rectangle has a non-virtual call")
                    require(
                        !target.relative && target.index == null && target.base != null &&
                                (registers[target.base] as? Table)?.receiver == registers[7] &&
                                target.displacement >= 0 && target.displacement % 8 == 0L &&
                                target.displacement / 8 <= 4095
                    ) { "Rectangle dispatch has an unverified table/receiver at $offset" }
                    pointer(registers[7])
                    require((8 + top()) % 16 == 0L) { "Rectangle call frame is unaligned" }
                    val slot = (target.displacement / 8).toInt()
                    if (slots.add(slot)) validateSlot(slot)
                    volatile.forEach { registers[it] = Unknown }
                    registers[0] = Scalar
                    state.flags = false
                    state.tested = null
                }

                Operation.JMP, Operation.JCC -> {
                    val target = (instruction.destination as? Immediate)?.value ?: error("Indirect rectangle branch")
                    if (instruction.operation == Operation.JCC) require(state.flags)
                    if (target <= offset) {
                        val member = state.tested ?: error("Rectangle loop is not guarded by its parent pointer")
                        val first = body[target]
                        val carried = first?.operation == Operation.MOV &&
                                first.destination == Register(member.base, 8) &&
                                (first.source as? Register)?.let { registers[it.number] == member } == true
                        require(
                            instruction.operation == Operation.JCC && instruction.condition == 5 &&
                                    (registers.getOrNull(member.base) == member || carried)
                        ) { "Rectangle loop does not advance to its parent" }
                        backParents.add(member.offset)
                    } else if (instruction.operation == Operation.JCC && instruction.condition == 4) {
                        state.tested?.let { guardedParents.add(it.offset) }
                    }
                    val taken = state.copyState()
                    fun nullBranch(branch: State) {
                        val tested = branch.tested ?: return
                        branch.registers.indices.forEach { register ->
                            if (branch.registers[register] == tested) branch.registers[register] = NullPointer
                        }
                        branch.stack.entries.forEach { entry ->
                            if (entry.value == tested) entry.setValue(NullPointer)
                        }
                    }
                    if (instruction.operation == Operation.JCC && instruction.condition == 4) nullBranch(taken)
                    if (instruction.operation == Operation.JCC && instruction.condition == 5) nullBranch(state)
                    enqueue(target, taken)
                    fallsThrough = instruction.operation == Operation.JCC
                }

                Operation.RET -> {
                    require(top() == 0L && preserved.all { registers[it] == Original(it) }) {
                        "Rectangle getter does not restore its caller's frame"
                    }
                    for (register in listOf(0, 2)) require(
                        registers[register] == Scalar ||
                                (registers[register] as? Member)?.width == 8
                    ) { "Rectangle does not return two complete integer words" }
                    returns++
                    fallsThrough = false
                }

                else -> error("Unsupported rectangle ABI instruction: ${instruction.operation}")
            }
            if (fallsThrough) enqueue(offset + instruction.size, state)
        }
        require(returns > 0 && parents.size == 1 && parents == backParents && parents == guardedParents && slots.isNotEmpty()) {
            "Rectangle getter does not establish one guarded parent chain"
        }
        return Proof(parents.single(), slots)
    }
}
