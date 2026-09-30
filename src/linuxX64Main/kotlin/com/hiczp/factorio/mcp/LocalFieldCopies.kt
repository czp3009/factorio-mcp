package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Copies of selected load or return bytes into a later local reference or outgoing stack argument.
 * Only paths from the selected source site are analyzed.
 * This establishes neither entry-path dominance, callee ABI nor the pointee's object extent. Callers prove those
 * separately before interpreting a field or using it at runtime. Saved pointers are never recovered from locals.
 */
internal class LocalFieldCopies(
    private val flow: X64ControlFlow,
    // The caller independently establishes each selected edge for every input admitted by this proof.
    selectedEdges: Map<Long, Long> = emptyMap(),
) {
    data class Field(val offset: Long, val width: Int)
    private data class State(val registers: MutableList<List<Int?>>, val bytes: MutableMap<Long, Int>) {
        fun copyState() = State(registers.toMutableList(), bytes.toMutableMap())
    }

    private val frame = SysVLocalArgument(flow)
    private val successors = flow.successors.mapValues { (site, edges) ->
        selectedEdges[site]?.let { selected ->
            require(flow.body.getValue(site).operation == Operation.JCC && selected in edges)
            listOf(selected)
        } ?: edges
    }
    private val predecessors = mutableMapOf<Long, MutableSet<Long>>()

    init {
        require(selectedEdges.keys.all { it in flow.reachable })
        for ((site, edges) in successors) if (site in flow.reachable) {
            for (edge in edges) predecessors.getOrPut(edge) { mutableSetOf() }.add(site)
        }
    }

    fun fromReturn(
        source: Long,
        sink: Long,
        returnRegister: Int = 0,
        returnWidth: Int = 1,
        argument: Int = 6
    ): List<Field> {
        require(flow.body[source]?.operation == Operation.CALL)
        require(returnRegister in listOf(0, 16) && returnWidth in listOf(1, 2, 4, 8))
        return after(source, sink, returnRegister, returnWidth, argument)
    }

    fun fromRead(
        source: Long,
        sink: Long,
        argument: Int = 6,
        byteOffset: Int = 0,
        byteWidth: Int? = null
    ): List<Field> {
        val instruction = flow.body.getValue(source)
        require(
            instruction.operation in listOf(
                Operation.MOV,
                Operation.MOVZX,
                Operation.SCALAR_MOV,
                Operation.VECTOR_MOV
            )
        )
        val target = instruction.destination as? Register ?: error("Field source is not a register load")
        val memory = instruction.source as? Memory ?: error("Field source is not a memory load")
        require(target.width >= memory.width)
        val width = byteWidth ?: memory.width
        require(width in listOf(1, 2, 4, 8, 16) && byteOffset >= 0 && byteOffset <= memory.width - width)
        return after(source, sink, target.number, width, argument, sourceByte = byteOffset)
    }

    /** The caller must independently establish the selected register's meaning before this instruction. */
    fun fromRegister(source: Long, sink: Long, register: Int, width: Int, argument: Int = 6): List<Field> =
        after(source, sink, register, width, argument, includeSource = true)

    private fun after(
        source: Long, sink: Long, returnRegister: Int, returnWidth: Int, argument: Int,
        includeSource: Boolean = false, sourceByte: Int = 0
    ): List<Field> {
        require(
            source != sink && source in flow.reachable && sink in flow.reachable &&
                    flow.body[sink]?.operation == Operation.CALL
        )
        require(returnRegister in 0..31 && returnRegister != 4 && returnWidth in listOf(1, 2, 4, 8, 16))
        require(sourceByte in 0..16 - returnWidth)
        val storage = if (argument == 4) frame.outgoing(sink, 1) else frame.argument(sink, argument, 1)
        val descendants = mutableSetOf<Long>()
        val pending = ArrayDeque<Long>()
        if (includeSource) descendants.add(source)
        pending.addAll(successors.getValue(source))
        while (pending.isNotEmpty()) {
            val offset = pending.removeFirst()
            require(offset != source) { "Return-field path repeats the selected source call" }
            if (descendants.add(offset) && offset != sink) pending.addAll(successors.getValue(offset))
        }
        require(sink in descendants) { "Selected call cannot reach the local argument" }
        val needed = mutableSetOf<Long>()
        pending.add(sink)
        while (pending.isNotEmpty()) {
            val offset = pending.removeFirst()
            if (offset in descendants && needed.add(offset)) pending.addAll(predecessors[offset].orEmpty())
        }
        require(needed.size <= 1024)
        val starts = if (includeSource) listOf(source) else successors.getValue(source).filter { it in needed }
        require(starts.isNotEmpty())
        val counts =
            needed.associateWith { offset -> predecessors[offset].orEmpty().count { it in needed } }.toMutableMap()
        val order = mutableListOf<Long>()
        pending.addAll(needed.filter { counts.getValue(it) == 0 })
        while (pending.isNotEmpty()) {
            val offset = pending.removeFirst()
            order += offset
            if (offset != sink) for (next in successors.getValue(offset).filter { it in needed }) {
                counts[next] = counts.getValue(next) - 1
                if (counts[next] == 0) pending.add(next)
            }
        }
        require(order.size == needed.size) { "Return-field path contains a cycle" }
        fun unknown(width: Int = 16): List<Int?> = List(width) { null }
        val initial = State(MutableList(32) { unknown() }, mutableMapOf())
        initial.registers[returnRegister] = List(16) {
            if (it in sourceByte until sourceByte + returnWidth) it - sourceByte else null
        }
        val incoming = starts.associateWith { initial.copyState() }.toMutableMap()
        for (position in order) {
            val state = incoming[position]?.copyState() ?: error("Return-field path has an unproven entry")
            if (position == sink) continue
            val instruction = flow.body.getValue(position)
            fun width(operand: X64Instructions.Operand?): Int = when (operand) {
                is Memory -> operand.width
                is Register -> operand.width
                else -> 8
            }

            fun read(operand: X64Instructions.Operand?): List<Int?> = when (operand) {
                is Register -> state.registers[operand.number].take(operand.width)
                is Memory -> {
                    val address = frame.address(position, operand)
                    if (address == null) unknown(operand.width) else {
                        require(address >= checkNotNull(frame.registers(position)[4]) && address <= -operand.width)
                        List(operand.width) { state.bytes[address + it] }
                    }
                }

                else -> unknown(width(operand))
            }

            fun write(operand: X64Instructions.Operand?, value: List<Int?>) {
                when (operand) {
                    is Register -> {
                        // Bytes outside the written portion are deliberately forgotten, including XMM upper lanes.
                        state.registers[operand.number] =
                            List(16) { if (it < operand.width) value.getOrNull(it) else null }
                    }

                    is Memory -> {
                        val address = frame.address(position, operand)
                        if (address == null) state.bytes.clear() else {
                            require(address >= checkNotNull(frame.registers(position)[4]) && address <= -operand.width)
                            for (index in 0 until operand.width) {
                                state.bytes.remove(address + index)
                                value.getOrNull(index)?.let { state.bytes[address + index] = it }
                            }
                        }
                    }

                    else -> error("Unsupported return-field destination")
                }
            }
            when (instruction.operation) {
                Operation.MOV, Operation.MOVZX, Operation.SCALAR_MOV, Operation.VECTOR_MOV ->
                    write(instruction.destination, read(instruction.source))

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

                Operation.CALL -> {
                    state.bytes.clear() // Borrowed local storage may have been mutated by any intervening call.
                    for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)) state.registers[register] =
                        unknown()
                }

                Operation.PUSH, Operation.POP -> {
                    state.bytes.clear()
                    if (instruction.operation == Operation.POP) write(instruction.destination, unknown())
                }

                Operation.CMP, Operation.TEST, Operation.SCALAR_COMPARE, Operation.NOP, Operation.ENDBR,
                Operation.JCC, Operation.JMP, Operation.RET -> Unit

                else -> write(instruction.destination, unknown())
            }
            for (next in successors.getValue(position).filter { it in needed }) {
                val old = incoming[next]
                incoming[next] = if (old == null) state.copyState() else State(
                    old.registers.mapIndexed { register, values ->
                        values.mapIndexed { index, byte ->
                            byte.takeIf { it == state.registers[register][index] }
                        }
                    }.toMutableList(),
                    old.bytes.filter { (address, byte) -> state.bytes[address] == byte }.toMutableMap()
                )
            }
        }
        val result = incoming.getValue(sink).bytes
        return result.filter { (address, byte) ->
            address >= storage && byte == 0 &&
                    (0 until returnWidth).all { result[address + it] == it }
        }
            .keys.sorted().map { Field(it - storage, returnWidth) }
    }
}
