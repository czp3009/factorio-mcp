package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Proves SDL integer coordinates are multiplied by the original scale and truncated to native pixels. */
internal class SdlPointerCoordinates(
    private val flow: X64ControlFlow,
    private val sdkExtent: Long,
    borrows: Map<Long, List<ConstructorValues.Borrow>> = emptyMap(),
) {
    private enum class Stage { INTEGER, FLOAT, SCALED_FLOAT, PIXEL }
    private sealed interface Lane
    private data object Scale : Lane
    private data class Coordinate(val offset: Long, val stage: Stage) : Lane
    private data class State(val registers: MutableList<List<Lane?>>, val locals: MutableMap<Long, Lane>) {
        fun copyState() = State(registers.toMutableList(), locals.toMutableMap())
    }

    private val frame = SysVLocalArgument(flow)
    private val arguments = SysVArgumentFlow(flow)
    private val before = mutableMapOf<Long, State>()

    init {
        require(sdkExtent in 4..256)
        ConstructorValues(flow, borrows)
        val exposed = borrows.flatMap { (site, entries) -> entries.map {
            frame.argument(site, it.register, it.extent) to it.extent
        } }
        val initial = MutableList<List<Lane?>>(32) { List(4) { null } }
        initial[16] = listOf(Scale, null, null, null)
        before[0] = State(initial, mutableMapOf())
        val pending = ArrayDeque<Long>()
        pending.add(0)
        var steps = 0
        while (pending.isNotEmpty()) {
            require(++steps <= 262144) { "SDL coordinate provenance exceeds bound" }
            val site = pending.removeFirst()
            val instruction = flow.body.getValue(site)
            val state = before.getValue(site).copyState()
            fun local(memory: Memory): Long? = frame.address(site, memory)?.also {
                require(it >= checkNotNull(frame.registers(site)[4]) && it <= -memory.width)
            }
            fun read(operand: Operand?): List<Lane?> = when (operand) {
                is Register -> state.registers[operand.number].take(operand.width / 4)
                is Memory -> {
                    val slot = local(operand)
                    val input = arguments.memory(site, operand)
                    when {
                        slot != null -> List(operand.width / 4) { index ->
                            val at = slot + index * 4
                            state.locals[at].takeUnless { exposed.any { (start, size) -> at < start + size && start < at + 4 } }
                        }
                        input?.reference?.argument == 2 && input.width == operand.width &&
                                operand.width in listOf(4, 8, 16) && input.reference.offset >= 0 &&
                                input.reference.offset <= sdkExtent - operand.width ->
                            List(operand.width / 4) { Coordinate(input.reference.offset + it * 4, Stage.INTEGER) }
                        else -> emptyList()
                    }
                }
                else -> emptyList()
            }
            fun write(operand: Operand?, lanes: List<Lane?>) {
                when (operand) {
                    is Register -> state.registers[operand.number] = List(4) { index ->
                        lanes.getOrNull(index).takeIf { index * 4 < operand.width }
                    }
                    is Memory -> local(operand)?.let { slot ->
                        state.locals.keys.removeAll { it < slot + operand.width && slot < it + 4 }
                        if (operand.width in listOf(4, 8, 16))
                            for (index in 0 until operand.width / 4)
                                lanes.getOrNull(index)?.let { state.locals[slot + index * 4] = it }
                    }
                    else -> Unit
                }
            }
            fun convert(input: List<Lane?>, from: Stage, to: Stage) = input.map {
                (it as? Coordinate)?.takeIf { value -> value.stage == from }?.copy(stage = to)
            }
            when (instruction.operation) {
                Operation.MOV, Operation.MOVZX, Operation.MOVSX, Operation.SCALAR_MOV, Operation.VECTOR_MOV ->
                    write(instruction.destination, read(instruction.source))
                Operation.VECTOR_INTS_TO_FLOATS ->
                    write(instruction.destination, convert(read(instruction.source), Stage.INTEGER, Stage.FLOAT))
                Operation.VECTOR_TRUNCATE_FLOATS ->
                    write(instruction.destination, convert(read(instruction.source), Stage.SCALED_FLOAT, Stage.PIXEL))
                Operation.VECTOR_SHUFFLE_FLOATS -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    val control = checkNotNull(instruction.control)
                    require(control in 0..255)
                    write(instruction.destination, listOf(left.getOrNull(control and 3),
                        left.getOrNull(control shr 2 and 3), right.getOrNull(control shr 4 and 3),
                        right.getOrNull(control shr 6 and 3)))
                }
                Operation.VECTOR_SHUFFLE_DWORDS -> {
                    val source = read(instruction.source)
                    val control = checkNotNull(instruction.control)
                    require(control in 0..255)
                    write(instruction.destination, List(4) { source.getOrNull(control shr (it * 2) and 3) })
                }
                Operation.VECTOR_MULTIPLY_FLOATS -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    write(instruction.destination, List(4) { lane ->
                        val a = left.getOrNull(lane)
                        val b = right.getOrNull(lane)
                        val coordinate = if (a == Scale) b else if (b == Scale) a else null
                        (coordinate as? Coordinate)?.takeIf { it.stage == Stage.FLOAT }
                            ?.copy(stage = Stage.SCALED_FLOAT)
                    })
                }
                Operation.CMOV -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    write(instruction.destination, left.mapIndexed { index, lane ->
                        lane.takeIf { it == right.getOrNull(index) }
                    })
                }
                Operation.CALL -> {
                    // A permitted borrowed range may be overwritten; no other private spill is exposed.
                    for (borrow in borrows[site].orEmpty()) {
                        val slot = frame.argument(site, borrow.register, borrow.extent)
                        state.locals.keys.removeAll { it < slot + borrow.extent && slot < it + 4 }
                    }
                    for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31))
                        state.registers[register] = List(4) { null }
                }
                Operation.PUSH -> {
                    val slot = checkNotNull(frame.registers(site)[4]) - 8
                    val lanes = read(instruction.destination)
                    state.locals.keys.removeAll { it < slot + 8 && slot < it + 4 }
                    repeat(2) { index -> lanes.getOrNull(index)?.let { state.locals[slot + index * 4] = it } }
                }
                Operation.POP -> {
                    val slot = checkNotNull(frame.registers(site)[4])
                    write(instruction.destination, List(2) { state.locals[slot + it * 4] })
                    state.locals.keys.removeAll { it < slot + 8 && slot < it + 4 }
                }
                Operation.CMP, Operation.TEST, Operation.BIT_TEST, Operation.SCALAR_COMPARE,
                Operation.NOP, Operation.ENDBR, Operation.JMP, Operation.JCC, Operation.RET -> Unit
                else -> write(instruction.destination, emptyList())
            }
            if (instruction.destination == Register(4, 8) && instruction.operation in listOf(Operation.ADD, Operation.SUB)) {
                val amount = (instruction.source as Immediate).value
                val top = checkNotNull(frame.registers(site)[4]) +
                        if (instruction.operation == Operation.ADD) amount else -amount
                state.locals.keys.removeAll { it < top }
            }
            for (next in flow.successors.getValue(site)) {
                val old = before[next]
                val merged = if (old == null) state.copyState() else State(
                    old.registers.mapIndexed { register, lanes -> lanes.mapIndexed { index, lane ->
                        lane.takeIf { it == state.registers[register][index] }
                    } }.toMutableList(), old.locals.filter { (slot, lane) -> state.locals[slot] == lane }.toMutableMap())
                if (old != merged) {
                    before[next] = merged
                    pending.add(next)
                }
            }
        }
    }

    fun fields(site: Long, register: Register): List<Long> {
        require(site in flow.reachable && register.width in listOf(4, 8, 16))
        return before.getValue(site).registers[register.number].take(register.width / 4).map {
            val value = it as? Coordinate ?: error("Native mouse position has no SDL coordinate provenance")
            require(value.stage == Stage.PIXEL) { "Native mouse position has not applied the original scale" }
            value.offset
        }
    }
}
