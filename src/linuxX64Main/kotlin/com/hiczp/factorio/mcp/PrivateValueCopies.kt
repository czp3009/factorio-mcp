package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Byte identity through registers and unexposed locals. Callers separately prove the meaning of produced values. */
internal class PrivateValueCopies(
    private val flow: X64ControlFlow,
    reads: Map<Long, Read>,
    produced: Map<Long, Read> = emptyMap(),
    private val scalarArguments: Map<Int, Int> = emptyMap(),
) {
    data class Read(val source: Long, val field: InlineArgumentFields.Field)
    private data class ByteSource(val source: Long, val offset: Long)
    private data class State(val registers: MutableList<List<ByteSource?>>, val locals: MutableMap<Long, ByteSource>) {
        fun copyState() = State(registers.toMutableList(), locals.toMutableMap())
    }

    private val frame = SysVLocalArgument(flow)
    private val before = mutableMapOf<Long, State>()

    init {
        // Empty borrow contracts require that no local pointer reaches an external call/store. This
        // permits retaining scalar spills across calls without assuming that borrowed output is unchanged.
        ConstructorValues(flow, emptyMap())
        require(
            reads.size + produced.size + scalarArguments.size in 1..64 && reads.keys.intersect(produced.keys).isEmpty()
        )
        require(scalarArguments.all { (register, width) ->
            register in listOf(7, 6, 2, 1, 8, 9) && width in listOf(
                1,
                2,
                4,
                8
            )
        })
        require(scalarArguments.isEmpty() || (reads.values + produced.values).all { it.source >= 0 })
        for ((site, read) in reads) {
            val field = read.field
            val instruction = flow.body.getValue(site)
            val source = instruction.source as? Memory ?: error("Selected copy source is not a memory read")
            val target =
                instruction.destination as? Register ?: error("Selected copy source is not loaded into a register")
            require(
                site in flow.reachable && instruction.operation in listOf(
                    Operation.MOV, Operation.MOVZX,
                    Operation.SCALAR_MOV, Operation.VECTOR_MOV
                ) && field.width == source.width && target.width >= source.width &&
                        field.width in listOf(1, 2, 4, 8, 16) && field.offset in 0..4096L - field.width
            )
        }
        for ((site, value) in produced) {
            val instruction = flow.body.getValue(site)
            val target = instruction.destination as? Register ?: error("Selected producer has no register result")
            require(
                site in flow.reachable && instruction.operation in listOf(
                    Operation.DOUBLE_DIVIDE,
                    Operation.DOUBLE_MULTIPLY
                ) && target.number in 16..31 && target.width == 8 &&
                        value.field.width == 8 && value.field.offset in 0..4088
            )
        }
        val initial = MutableList<List<ByteSource?>>(32) { List(16) { null } }
        for ((register, width) in scalarArguments) {
            initial[register] =
                List(16) { byte -> if (byte < width) ByteSource(-1L - register, byte.toLong()) else null }
        }
        before[0] = State(initial, mutableMapOf())
        val pending = ArrayDeque<Long>()
        pending.add(0)
        var steps = 0
        while (pending.isNotEmpty()) {
            require(++steps <= 262144) { "Private byte-copy analysis exceeds bound" }
            val site = pending.removeFirst()
            val state = before.getValue(site).copyState()
            val instruction = flow.body.getValue(site)
            fun location(memory: Memory): Long? = frame.address(site, memory)?.also {
                require(it >= checkNotNull(frame.registers(site)[4]) && it <= -memory.width)
            }

            fun read(operand: Operand?): List<ByteSource?> = when (operand) {
                is Register -> state.registers[operand.number].take(operand.width)
                is Memory -> {
                    val slot = location(operand)
                    List(operand.width) { if (slot == null) null else state.locals[slot + it] }
                }

                else -> emptyList()
            }

            fun write(operand: Operand?, value: List<ByteSource?>) {
                when (operand) {
                    is Register -> state.registers[operand.number] = List(16) { index ->
                        value.getOrNull(index).takeIf { index < operand.width }
                    }

                    is Memory -> location(operand)?.let { slot ->
                        for (byte in 0 until operand.width) {
                            state.locals.remove(slot + byte)
                            value.getOrNull(byte)?.let { state.locals[slot + byte] = it }
                        }
                    }

                    else -> error("Unsupported private byte-copy destination")
                }
            }
            when (instruction.operation) {
                Operation.MOV, Operation.MOVZX, Operation.SCALAR_MOV, Operation.VECTOR_MOV -> {
                    val source = reads[site]?.let { input ->
                        List(input.field.width) { ByteSource(input.source, input.field.offset + it) }
                    }
                        ?: read(instruction.source)
                    write(instruction.destination, source)
                }

                Operation.XCHG -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    write(instruction.destination, right)
                    write(instruction.source, left)
                }

                Operation.CMOV -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    write(
                        instruction.destination,
                        left.mapIndexed { index, byte -> byte.takeIf { it == right.getOrNull(index) } })
                }

                Operation.CALL -> for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31))
                    state.registers[register] = List(16) { null }

                Operation.PUSH -> {
                    val slot = checkNotNull(frame.registers(site)[4]) - 8
                    val bytes = read(instruction.destination)
                    repeat(8) { byte ->
                        state.locals.remove(slot + byte)
                        bytes.getOrNull(byte)?.let { state.locals[slot + byte] = it }
                    }
                }

                Operation.POP -> {
                    val slot = checkNotNull(frame.registers(site)[4])
                    write(instruction.destination, List(8) { state.locals[slot + it] })
                    repeat(8) { state.locals.remove(slot + it) }
                }

                Operation.CMP, Operation.TEST, Operation.BIT_TEST, Operation.SCALAR_COMPARE, Operation.NOP,
                Operation.ENDBR, Operation.JMP, Operation.JCC, Operation.RET -> Unit

                else -> write(instruction.destination, emptyList())
            }
            produced[site]?.let { value ->
                write(
                    instruction.destination,
                    List(value.field.width) { ByteSource(value.source, value.field.offset + it) })
            }
            if (instruction.destination == Register(4, 8) && instruction.operation in listOf(
                    Operation.ADD,
                    Operation.SUB
                )
            ) {
                val amount = (instruction.source as X64Instructions.Immediate).value
                val top =
                    checkNotNull(frame.registers(site)[4]) + if (instruction.operation == Operation.ADD) amount else -amount
                state.locals.keys.removeAll { it < top }
            }
            for (next in flow.successors.getValue(site)) {
                val old = before[next]
                val merged = if (old == null) state.copyState() else State(
                    old.registers.mapIndexed { register, bytes ->
                        bytes.mapIndexed { byte, value ->
                            value.takeIf { it == state.registers[register][byte] }
                        }
                    }.toMutableList(), old.locals.filter { (slot, value) -> state.locals[slot] == value }.toMutableMap()
                )
                if (old != merged) {
                    before[next] = merged
                    pending.add(next)
                }
            }
        }
    }

    fun field(site: Long, register: Register): Read {
        require(register.number in 0..31 && register.width in listOf(1, 2, 4, 8, 16) && site in flow.reachable)
        val bytes = before.getValue(site).registers[register.number].take(register.width)
        val first = bytes.firstOrNull() ?: error("Copy result has no original input bytes")
        require(bytes.withIndex().all { (index, value) -> value == first.copy(offset = first.offset + index) }) {
            "Copied scalar is partial, changed or assembled from different input fields"
        }
        return Read(first.source, InlineArgumentFields.Field(first.offset, register.width))
    }

    fun argument(site: Long, register: Register): Int {
        val value = field(site, register)
        val original = -1L - value.source
        require(original in 0..15 && scalarArguments[original.toInt()] == register.width && value.field.offset == 0L) {
            "Scalar copy is not one complete original argument"
        }
        return original.toInt()
    }
}
