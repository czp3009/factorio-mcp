package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Verifies output/source/new-widget arguments and derives event fields from native coordinate rebasing. */
internal object MouseEventCopy {
    data class Proof(val extent: Int, val source: Long, val x: Long, val y: Long)

    private sealed interface Value
    private data object Unknown : Value
    private data object Output : Value
    private data object Event : Value
    private data object Null : Value
    private data class Original(val register: Int) : Value
    private data class Stack(val offset: Long) : Value
    private data class Data(val offset: Long, val width: Int) : Value
    private data class Widget(
        val source: Long?, val parent: Boolean = false, val parentBase: Int = -1,
        val checked: Boolean = true
    ) : Value

    private data class Table(val receiver: Value) : Value
    private data class Scalar(val fields: Set<Long> = emptySet(), val slots: Set<Int> = emptySet()) : Value
    private data class Packed(val low: Scalar?, val high: Scalar) : Value
    private data class State(
        val registers: MutableList<Value>,
        val stack: MutableMap<Pair<Long, Int>, Value>,
        val output: MutableMap<Pair<Long, Int>, Value>,
        val copied: MutableSet<Int> = mutableSetOf(),
        var tested: Widget? = null,
    ) {
        fun copyState() =
            State(registers.toMutableList(), stack.toMutableMap(), output.toMutableMap(), copied.toMutableSet(), tested)
    }

    fun resolve(
        image: ElfImage, function: ElfImage.Symbol, widgetSize: Long,
        parent: Long, horizontalPadding: Int, verticalPadding: Int
    ): Proof {
        EhFrames(image).function(function)
        return analyze(image.functionBytes(function, 4096), widgetSize, parent, horizontalPadding, verticalPadding)
    }

    fun analyze(
        bytes: BinaryView, widgetSize: Long, parent: Long,
        horizontalPadding: Int, verticalPadding: Int
    ): Proof {
        require(bytes.size in 1..4096 && widgetSize in 8..(16 * 1024 * 1024) && parent in 8..widgetSize - 8)
        require(horizontalPadding in 0..4095 && verticalPadding in 0..4095 && horizontalPadding != verticalPadding)
        val body = X64Instructions(bytes).all(1024).associateBy { it.offset }
        val incoming = mutableMapOf<Long, State>()
        val pending = ArrayDeque<Long>()
        val sourceFields = mutableSetOf<Long>()
        val sourceReads = mutableSetOf<Pair<Long, Int>>()
        val loopSources = mutableSetOf<Long?>()
        val results = mutableSetOf<Proof>()
        val returns = mutableSetOf<Long>()
        val preserved = setOf(3, 5, 12, 13, 14, 15)
        fun scalar(value: Value): Scalar = when (value) {
            is Scalar -> value
            is Data -> Scalar(setOf(value.offset)).also { require(value.width == 4) }
            else -> error("Event coordinate arithmetic consumes an unproven scalar")
        }

        fun merge(left: Value, right: Value): Value = when {
            left == right -> left
            left is Table && right is Table -> Table(merge(left.receiver, right.receiver))
            left is Packed && right is Packed && (left.low == null) == (right.low == null) -> Packed(
                left.low?.let { scalar(merge(it, checkNotNull(right.low))) }, scalar(merge(left.high, right.high))
            )

            left is Scalar && right is Scalar -> Scalar(left.fields + right.fields, left.slots + right.slots)
            left is Data && left.width == 4 && right is Scalar -> scalar(left).let {
                Scalar(
                    it.fields + right.fields,
                    right.slots
                )
            }

            right is Data && right.width == 4 && left is Scalar -> scalar(right).let {
                Scalar(
                    it.fields + left.fields,
                    left.slots
                )
            }

            left is Widget && right is Widget && left.source == right.source ->
                Widget(left.source, checked = left.checked && right.checked)

            left is Data && left.width == 8 && right is Widget && right.source == left.offset -> Widget(left.offset)
            right is Data && right.width == 8 && left is Widget && left.source == right.offset -> Widget(right.offset)
            left is Widget && right == Null || right is Widget && left == Null -> Null
            else -> Unknown
        }

        fun enqueue(offset: Long, state: State) {
            require(offset in body) { "Event copy branch leaves the decoded function" }
            val previous = incoming[offset]
            if (previous == null) {
                incoming[offset] = state.copyState()
                pending += offset
            } else {
                require(previous.registers[4] == state.registers[4]) { "Event copy paths disagree on stack depth" }
                val combined = State(
                    previous.registers.mapIndexed { index, value ->
                        merge(value, state.registers[index])
                    }.toMutableList(),
                    previous.stack.mapValues { (key, value) ->
                        merge(value, state.stack[key] ?: Unknown)
                    }.toMutableMap(),
                    previous.output.mapValues { (key, value) ->
                        merge(value, state.output[key] ?: Unknown)
                    }.toMutableMap(),
                    previous.copied.intersect(state.copied).toMutableSet(),
                    previous.tested.takeIf { it == state.tested })
                if (combined != previous) {
                    incoming[offset] = combined
                    pending += offset
                }
            }
        }

        val registers = MutableList<Value>(32) { Original(it) }
        registers[7] = Output
        registers[6] = Event
        registers[2] = Widget(null)
        registers[4] = Stack(0)
        enqueue(0, State(registers, mutableMapOf(), mutableMapOf()))
        var steps = 0
        while (pending.isNotEmpty()) {
            require(++steps <= 16384) { "Event copy dataflow exceeds its bound" }
            val offset = pending.removeFirst()
            val state = incoming.getValue(offset).copyState()
            val instruction = body.getValue(offset)
            val values = state.registers
            fun top() = (values[4] as? Stack)?.offset ?: error("Event copy lost its frame")
            fun frame(memory: Memory): Long {
                val base = values[checkNotNull(memory.base)] as? Stack ?: error("Unproven event copy frame address")
                val location = base.offset + memory.displacement
                require(location >= top() && location <= -memory.width) { "Event copy exceeds its reserved frame" }
                return location
            }

            fun widget(value: Value): Widget = when (value) {
                is Widget -> value
                is Data -> Widget(value.offset).also {
                    require(value.width == 8)
                    sourceFields += value.offset
                }

                else -> error("Event copy dereferences an unproven Widget")
            }

            fun read(operand: Operand?): Value = when (operand) {
                is Immediate -> Scalar()
                is Register -> when (val value = values[operand.number]) {
                    is Data -> {
                        if (operand.width == 8 && value.width == 4 && operand.number < 16) {
                            // A 32-bit general-register write zero-extends the complete architectural register.
                            Scalar(setOf(value.offset))
                        } else {
                            require(operand.width <= value.width) { "Event copy widens an unproved input at $offset" }
                            value.copy(width = operand.width)
                        }
                    }

                    is Scalar -> value
                    else -> value.also { require(operand.width == 8 || operand.width == 16 && operand.number >= 16) }
                }

                is Memory -> {
                    require(!operand.relative && operand.index == null && operand.base != null)
                    when (val base = values[operand.base]) {
                        is Stack -> state.stack[frame(operand) to operand.width] ?: Unknown
                        Event -> {
                            require(operand.width in listOf(4, 8, 16) && operand.displacement in 0..256 - operand.width)
                            sourceReads += operand.displacement to operand.width
                            Data(operand.displacement, operand.width)
                        }

                        else -> {
                            val receiver = widget(base)
                            require(receiver.checked) { "Event copy dereferences a parent before checking for null" }
                            require(operand.displacement in 0..widgetSize - operand.width)
                            when {
                                operand.displacement == 0L && operand.width == 8 -> Table(receiver)
                                operand.displacement == parent && operand.width == 8 ->
                                    receiver.copy(parent = true, parentBase = operand.base, checked = false)

                                operand.width == 4 -> Scalar()
                                else -> error("Event copy uses an unsupported Widget member")
                            }
                        }
                    }
                }

                else -> error("Unsupported event copy input")
            }

            fun write(target: Operand?, value: Value) {
                when (target) {
                    is Register -> {
                        require(target.number != 4 && target.width in listOf(4, 8, 16))
                        require(target.width != 4 || value is Data && value.width == 4 || value is Scalar)
                        values[target.number] = value
                    }

                    is Memory -> {
                        require(!target.relative && target.index == null && target.base != null)
                        val region = values[target.base]
                        val at = if (region is Stack) frame(target) else target.displacement.also {
                            require(region == Output && it in 0..256 - target.width)
                        }
                        val storage = if (region is Stack) state.stack else state.output
                        require(storage.none { (key, saved) ->
                            saved is Stack && key.first < at + target.width && at < key.first + key.second &&
                                    !(at <= key.first && key.first + key.second <= at + target.width)
                        }) { "Event copy partially overwrites a frame pointer" }
                        val overlaps =
                            storage.filterKeys { (start, width) -> start < at + target.width && at < start + width }
                        storage.keys.removeAll(overlaps.keys)
                        if (region == Output) for ((cell, saved) in overlaps) {
                            if (cell.first < at) {
                                require(saved is Data && saved.offset == cell.first && saved.width == cell.second)
                                storage[cell.first to (at - cell.first).toInt()] =
                                    Data(cell.first, (at - cell.first).toInt())
                            }
                            val right = at + target.width
                            if (right < cell.first + cell.second) {
                                require(saved is Data && saved.offset == cell.first && saved.width == cell.second)
                                storage[right to (cell.first + cell.second - right).toInt()] =
                                    Data(right, (cell.first + cell.second - right).toInt())
                            }
                        }
                        storage[at to target.width] = value
                        if (region == Output && value is Data) {
                            require(at == value.offset && target.width == value.width) { "Event copy reorders source bytes" }
                            repeat(target.width) { state.copied += at.toInt() + it }
                        }
                        require(region is Stack || value !is Stack && value !is Original && value != Unknown)
                    }

                    else -> error("Unsupported event copy destination")
                }
            }

            var fallsThrough = true
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    val value = read(instruction.destination)
                    val next = top() - 8
                    require(next >= -4096)
                    values[4] = Stack(next)
                    state.stack[next to 8] = value
                }

                Operation.POP -> {
                    val at = top()
                    require(at < 0 && instruction.destination != Register(4, 8))
                    write(instruction.destination, state.stack.remove(at to 8) ?: Unknown)
                    values[4] = Stack(at + 8)
                }

                Operation.MOV, Operation.VECTOR_MOV -> write(instruction.destination, read(instruction.source))
                Operation.ADD, Operation.SUB -> {
                    if (instruction.destination == Register(4, 8)) {
                        val amount = (instruction.source as? Immediate)?.value ?: error("Variable event copy frame")
                        require(amount in 0..4096 && amount % 8 == 0L)
                        val next = top() + if (instruction.operation == Operation.ADD) amount else -amount
                        require(next in -4096..0)
                        values[4] = Stack(next)
                    } else {
                        val left = scalar(read(instruction.destination))
                        val right = scalar(read(instruction.source))
                        write(instruction.destination, Scalar(left.fields + right.fields, left.slots + right.slots))
                    }
                    state.tested = null
                }

                Operation.XOR -> {
                    require(instruction.destination is Register && instruction.destination == instruction.source)
                    write(instruction.destination, Scalar())
                    state.tested = null
                }

                Operation.SHL -> {
                    require(
                        instruction.destination is Register && instruction.destination.width == 8 && instruction.source == Immediate(
                            32
                        )
                    )
                    write(instruction.destination, Packed(null, scalar(read(instruction.destination))))
                    state.tested = null
                }

                Operation.OR -> {
                    require(instruction.destination is Register && instruction.destination.width == 8)
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    val packed =
                        (left as? Packed) ?: (right as? Packed) ?: error("Event coordinates are not packed integers")
                    require(packed.low == null)
                    write(instruction.destination, packed.copy(low = scalar(if (left is Packed) right else left)))
                    state.tested = null
                }

                Operation.TEST -> {
                    require(instruction.destination == instruction.source)
                    state.tested = widget(read(instruction.destination)).also {
                        require(it.parent && !it.checked) { "Event parent guard must test a freshly loaded parent" }
                    }
                }

                Operation.CALL -> {
                    val target = instruction.destination as? Memory ?: error("Event copy makes a non-virtual call")
                    require(
                        !target.relative && target.index == null && target.base != null && target.width == 8 &&
                                target.displacement % 8 == 0L
                    )
                    val slot = (target.displacement / 8).toInt()
                    require(slot == horizontalPadding || slot == verticalPadding)
                    val receiver = widget(values[7])
                    require(receiver.checked) { "Event copy dispatches through an unchecked parent" }
                    require(values[target.base] == Table(receiver) && (8 + top()) % 16 == 0L) {
                        "Event padding call at $offset has a mismatched table/receiver or frame"
                    }
                    require(listOf(0, 1, 2, 6, 7, 8, 9, 10, 11).none { values[it] is Stack }) {
                        "Event copy exposes its frame to a virtual call"
                    }
                    for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)) values[register] = Unknown
                    values[0] = Scalar(slots = setOf(slot))
                    state.tested = null
                }

                Operation.JCC -> {
                    require(instruction.condition in listOf(4, 5))
                    val tested = checkNotNull(state.tested) { "Event copy branch has no parent null guard" }
                    val target = (instruction.destination as? Immediate)?.value ?: error("Indirect event copy branch")
                    if (target <= offset) {
                        require(instruction.condition == 5)
                        val direct = values[tested.parentBase] == tested
                        var carried = false
                        if (!direct) {
                            var next = target
                            repeat(8) {
                                if (carried) return@repeat
                                val entry = body[next] ?: error("Event parent loop leaves the function")
                                next += entry.size
                                if (entry.operation == Operation.MOV && entry.destination == Register(
                                        tested.parentBase,
                                        8
                                    ) &&
                                    (entry.source as? Register)?.let { values[it.number] == tested } == true
                                ) carried = true
                                else require(
                                    entry.operation == Operation.NOP || entry.operation == Operation.ENDBR ||
                                        entry.operation == Operation.MOV && (entry.destination as? Memory)?.let {
                                    !it.relative && it.index == null && it.base?.let { base -> values[base] is Stack } == true
                                } == true) { "Event loop does not carry its tested parent into the next iteration" }
                            }
                        }
                        require(direct || carried) { "Event loop at $offset does not advance" }
                        loopSources += tested.source
                    }
                    val taken = state.copyState()
                    fun mark(branch: State, isNull: Boolean) {
                        val value = if (isNull) Null else tested.copy(checked = true)
                        for (index in branch.registers.indices) if (branch.registers[index] == tested) branch.registers[index] =
                            value
                        branch.stack.entries.forEach { if (it.value == tested) it.setValue(value) }
                    }
                    mark(taken, instruction.condition == 4)
                    mark(state, instruction.condition == 5)
                    enqueue(target, taken)
                }

                Operation.RET -> {
                    require(top() == 0L && preserved.all { values[it] == Original(it) }) {
                        "Event copy does not restore its caller's frame"
                    }
                    returns += offset
                    fallsThrough = false
                }

                else -> error("Unsupported event copy instruction: ${instruction.operation}")
            }
            if (fallsThrough) enqueue(offset + instruction.size, state)
        }
        for (offset in returns) {
            val state = incoming.getValue(offset)
            val source = sourceFields.singleOrNull() ?: error("Event copy has multiple source Widget fields")
            val sourceCell = state.output.entries.singleOrNull { it.value == Widget(null) }
                ?: error("Event copy does not store the new source Widget")
            require(sourceCell.key == source to 8) { "Event source output field differs from its input" }
            val coordinates = mutableMapOf<Long, Scalar>()
            val coverage = mutableSetOf<Int>()
            for ((cell, value) in state.output) {
                when (value) {
                    is Packed -> {
                        require(cell.second == 8)
                        coordinates[cell.first] = checkNotNull(value.low)
                        coordinates[cell.first + 4] = value.high
                    }

                    is Scalar -> {
                        require(cell.second == 4)
                        coordinates[cell.first] = value
                    }

                    else -> require(cell == sourceCell.key || value == Data(cell.first, cell.second))
                }
                repeat(cell.second) { require(coverage.add(cell.first.toInt() + it)) }
            }
            require(coordinates.size == 2 && coordinates.all { (field, value) -> value.fields == setOf(field) }) {
                "Event coordinates do not preserve their input field association: $coordinates"
            }
            val x = coordinates.entries.singleOrNull { it.value.slots == setOf(horizontalPadding) }?.key
                ?: error("Horizontal event coordinate disagrees with the native padding axis")
            val y = coordinates.entries.singleOrNull { it.value.slots == setOf(verticalPadding) }?.key
                ?: error("Vertical event coordinate disagrees with the native padding axis")
            val extent = (state.output.keys.maxOfOrNull { it.first + it.second } ?: 0).toInt()
            require(extent in 8..256 && state.copied.containsAll((0 until extent).toList())) {
                "Event copy did not initialize the complete aggregate: extent=$extent, copied=${state.copied.sorted()}"
            }
            require(coverage == (0 until extent).toSet()) { "Event copy leaves an output gap" }
            results += Proof(extent, source, x, y)
        }
        val result = results.singleOrNull() ?: error("Event copy returns incompatible layouts")
        require(sourceReads.all { (offset, width) -> offset in 0..result.extent - width }) {
            "Event copy reads beyond its verified aggregate extent"
        }
        require(loopSources == setOf(null, result.source)) { "Event copy does not walk both Widget parent chains" }
        return result
    }
}
