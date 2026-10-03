package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Exact private string bytes backed by one unescaped allocation, across all paths to a native consumer. */
internal object HeapStringArgument {
    private sealed interface Value
    private data class Pointer(val heap: Boolean, val offset: Long) : Value
    private data class Bytes(val value: List<Byte>) : Value
    private data class State(
        val registers: MutableList<Value?>,
        val bytes: MutableMap<Pointer, Byte>,
        val pointers: MutableMap<Pointer, Pointer>,
    ) {
        fun copied() = State(registers.toMutableList(), bytes.toMutableMap(), pointers.toMutableMap())
    }

    fun text(
        flow: X64ControlFlow, call: Long, argument: Int, address: Long, string: NativeStringLayout,
        allocate: Long, read: (Long, Int) -> ByteArray,
    ): String {
        require(call in flow.reachable && flow.body.getValue(call).operation == Operation.CALL)
        require(string.size in 1..256 && string.data in 0..string.size - 8 && string.length in 0..string.size - 8)
        val reaching = flow.reaching(call)
        val locals = SysVLocalArgument(reaching)
        val objectAddress = locals.argument(call, argument, string.size.toInt())
        val constants = LocalStringArgument(reaching, string)
        val failures = mutableListOf<String>()
        val names = reaching.instructions.mapNotNull { instruction ->
            if (instruction.offset !in reaching.reachable || instruction.operation != Operation.CALL ||
                instruction.destination != Immediate(allocate)) return@mapNotNull null
            try {
                val size = constants.constant(instruction.offset, 7)
                require(size in 2..256)
                require(dominates(reaching, instruction.offset, call)) { "Literal allocation does not dominate its consumer" }
                observe(reaching, locals, instruction.offset, call, address, objectAddress, string, size, read)
            } catch (failure: IllegalArgumentException) {
                failures += failure.message.orEmpty()
                null
            } catch (failure: IllegalStateException) {
                failures += failure.message.orEmpty()
                null
            }
        }.distinct()
        return names.singleOrNull() ?: error("No unique owned heap string argument: ${failures.take(4).joinToString()}")
    }

    private fun dominates(flow: X64ControlFlow, selected: Long, target: Long): Boolean {
        val pending = ArrayDeque<Long>()
        val visited = mutableSetOf<Long>()
        pending.add(0)
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (site == target) return false
            if (site != selected && visited.add(site)) pending.addAll(flow.successors.getValue(site))
        }
        return true
    }

    private fun observe(
        flow: X64ControlFlow, locals: SysVLocalArgument, allocation: Long, call: Long,
        address: Long, objectAddress: Long, string: NativeStringLayout, size: Long,
        read: (Long, Int) -> ByteArray,
    ): String {
        val arguments = SysVArgumentFlow(flow)
        val first = flow.body.getValue(allocation).let { it.offset + it.size }
        val registers = MutableList<Value?>(32) { number ->
            locals.registers(first)[number]?.let { Pointer(false, it) }
        }
        registers[0] = Pointer(true, 0)
        val before = mutableMapOf(first to State(registers, mutableMapOf(), mutableMapOf()))
        val pending = ArrayDeque<Long>()
        pending.add(first)
        var work = 0
        fun constant(value: Long, width: Int) = Bytes(List(width) { (value ushr (it * 8)).toByte() })
        fun integer(value: Value?, width: Int): Long {
            require(value is Bytes && width in 1..8 && value.value.size >= width)
            return (0 until width).fold(0L) { result, index ->
                result or ((value.value[index].toLong() and 255) shl (8 * index))
            }
        }
        while (pending.isNotEmpty()) {
            require(++work <= 8192) { "Heap string construction exceeds its analysis bound" }
            val site = pending.removeFirst()
            if (site == call) continue
            val instruction = flow.body.getValue(site)
            val state = before.getValue(site).copied()
            fun span(pointer: Pointer, width: Int) {
                require(width in 1..256)
                if (pointer.heap) require(pointer.offset in 0..size - width)
                else require(pointer.offset >= checkNotNull(locals.registers(site)[4]) && pointer.offset <= -width)
            }
            fun location(memory: Memory): Pointer? {
                if (memory.relative) return null
                val base = memory.base?.let { state.registers[it] } as? Pointer ?: return null
                require(memory.index == null && memory.displacement in -16384..16384)
                return base.copy(offset = base.offset + memory.displacement)
            }
            fun load(pointer: Pointer, width: Int): Value? {
                span(pointer, width)
                if (width == 8) state.pointers[pointer]?.let { return it }
                val bytes = (0 until width).map { state.bytes[pointer.copy(offset = pointer.offset + it)] }
                return if (bytes.all { it != null }) Bytes(bytes.filterNotNull()) else null
            }
            fun value(operand: Operand?): Value? = when (operand) {
                is Immediate -> constant(operand.value, 8)
                is Register -> when (val source = state.registers[operand.number]) {
                    is Pointer -> source.also { require(operand.width == 8) }
                    is Bytes -> source.takeIf { it.value.size >= operand.width }?.let { Bytes(it.value.take(operand.width)) }
                    null -> null
                }
                is Memory -> if (operand.relative) {
                    require(operand.base == null && operand.index == null && operand.width in listOf(1, 2, 4, 8, 16))
                    val next = address + site + instruction.size
                    require(next >= address && operand.displacement >= -next && operand.displacement <= Long.MAX_VALUE - next)
                    Bytes(read(next + operand.displacement, operand.width).also { require(it.size == operand.width) }.toList())
                } else location(operand)?.let { load(it, operand.width) }
                else -> null
            }
            fun write(operand: Operand?, source: Value?) {
                when (operand) {
                    is Register -> {
                        require(operand.number != 4 && operand.width in listOf(1, 2, 4, 8, 16))
                        state.registers[operand.number] = when (source) {
                            is Pointer -> source.also { require(operand.width == 8) }
                            is Bytes -> {
                                require(source.value.size >= operand.width)
                                val bytes = source.value.take(operand.width)
                                if (operand.width in listOf(1, 2) && operand.number < 16) null
                                else Bytes(if (operand.width == 4 && operand.number < 16) bytes + List(4) { 0.toByte() } else bytes)
                            }
                            null -> null
                        }
                    }
                    is Memory -> {
                        val pointer = location(operand)
                        if (pointer == null) {
                            require(source !is Pointer && arguments.memory(site, operand)?.reference?.argument in
                                    listOf(7, 6, 2, 1, 8, 9)) { "Literal storage escapes or an unknown store can alias it" }
                        } else {
                            span(pointer, operand.width)
                            state.pointers.keys.removeAll {
                                it.heap == pointer.heap && it.offset < pointer.offset + operand.width && pointer.offset < it.offset + 8
                            }
                            repeat(operand.width) { state.bytes.remove(pointer.copy(offset = pointer.offset + it)) }
                            when (source) {
                                is Pointer -> {
                                    require(operand.width == 8)
                                    state.pointers[pointer] = source
                                }
                                is Bytes -> {
                                    require(source.value.size >= operand.width)
                                    repeat(operand.width) { state.bytes[pointer.copy(offset = pointer.offset + it)] = source.value[it] }
                                }
                                null -> Unit
                            }
                        }
                    }
                    else -> error("Unsupported heap literal destination")
                }
            }
            when (instruction.operation) {
                Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV -> write(instruction.destination, value(instruction.source))
                Operation.LEA -> write(instruction.destination, location(instruction.source as? Memory ?: error("Invalid literal address")))
                Operation.XOR, Operation.VECTOR_XOR -> {
                    require(instruction.destination is Register && instruction.destination == instruction.source)
                    write(instruction.destination, constant(0, instruction.destination.width))
                }
                Operation.ADD, Operation.SUB, Operation.INC, Operation.DEC -> {
                    val amount = if (instruction.operation in listOf(Operation.INC, Operation.DEC)) 1L
                    else (instruction.source as? Immediate)?.value ?: error("Variable heap literal adjustment")
                    require(amount in -16384..16384)
                    val delta = if (instruction.operation in listOf(Operation.SUB, Operation.DEC)) -amount else amount
                    val target = instruction.destination
                    val width = when (target) {
                        is Register -> target.width
                        is Memory -> target.width
                        else -> error("Invalid adjustment")
                    }
                    val old = value(target)
                    write(target, when (old) {
                        is Pointer -> old.copy(offset = old.offset + delta)
                        is Bytes -> constant(integer(old, width) + delta, width)
                        null -> null
                    })
                }
                Operation.NOP, Operation.ENDBR, Operation.CMP, Operation.TEST, Operation.JMP, Operation.JCC -> Unit
                else -> error("Heap literal calls or uses an unsupported operation before its consumer: ${instruction.operation}")
            }
            for (next in flow.successors.getValue(site)) {
                val previous = before[next]
                if (previous != null) {
                    require(previous.registers.indices.all { index ->
                        val left = previous.registers[index]
                        val right = state.registers[index]
                        left == right || left !is Pointer && right !is Pointer
                    } && (previous.pointers.keys + state.pointers.keys).all {
                        previous.pointers[it] == state.pointers[it]
                    }) { "Heap literal join loses a private pointer alias" }
                }
                val joined = if (previous == null) state.copied() else State(
                    previous.registers.mapIndexed { index, prior -> prior.takeIf { it == state.registers[index] } }.toMutableList(),
                    previous.bytes.filter { (key, byte) -> state.bytes[key] == byte }.toMutableMap(),
                    previous.pointers.filter { (key, pointer) -> state.pointers[key] == pointer }.toMutableMap(),
                )
                if (previous != joined) {
                    before[next] = joined
                    pending.add(next)
                }
            }
        }
        val result = before[call] ?: error("Owned literal has no consumer path")
        val objectPointer = Pointer(false, objectAddress)
        require(result.pointers[objectPointer.copy(offset = objectAddress + string.data)] == Pointer(true, 0))
        fun bytes(pointer: Pointer, count: Int): Bytes = Bytes(List(count) {
            result.bytes[pointer.copy(offset = pointer.offset + it)] ?: error("Owned literal has undefined bytes")
        })
        val length = integer(bytes(objectPointer.copy(offset = objectAddress + string.length), 8), 8)
        require(length in 1..128 && length < size)
        val content = bytes(Pointer(true, 0), length.toInt() + 1).value
        require(content.last() == 0.toByte() && content.dropLast(1).none { it == 0.toByte() })
        return content.dropLast(1).toByteArray().decodeToString(throwOnInvalidSequence = true)
    }
}
