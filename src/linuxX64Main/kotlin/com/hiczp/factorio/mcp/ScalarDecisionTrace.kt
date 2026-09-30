package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Ordered scalar call/read/decision traces, conditional on the supplied callees' argument contracts.
 * This compares decoded computations, not instruction bytes. It does not prove callee ABI, receiver type,
 * returned-object bounds, purity or callable entry safety. No game function is invoked by this analysis.
 */
internal class ScalarDecisionTrace(
    private val flow: X64ControlFlow,
    private val address: Long,
    private val calls: Map<Long, List<Register>>,
) {
    sealed interface Value {
        data object Receiver : Value
        data class Constant(val value: Long) : Value
        data class Returned(val event: Int) : Value
        data class Loaded(val event: Int) : Value
        data class Low(val value: Value, val width: Int) : Value
        data class Xor(val left: Value, val right: Value, val width: Int) : Value
        data class Equal(val left: Value, val right: Value, val equal: Boolean) : Value
    }

    sealed interface Event {
        data class Call(val target: Long, val arguments: List<Value>) : Event
        data class Read(val pointer: Value, val displacement: Long, val width: Int) : Event
        data class Equal(val left: Value, val right: Value, val equal: Boolean) : Event
    }

    data class Path(val events: List<Event>, val result: Value)
    private data class Scalar(val value: Value, val width: Int)
    private data class State(
        val registers: MutableList<Scalar?>,
        val events: MutableList<Event>,
        var comparison: Pair<Value, Value>? = null,
    ) {
        fun copyState() = State(registers.toMutableList(), events.toMutableList(), comparison)
    }

    private val frame = SysVLocalArgument(flow)

    fun paths(start: Long = 0, receiver: Int = 7, end: Long? = null, result: Register = Register(0, 1)): Set<Path> {
        require(start in flow.reachable && (end == null || end in flow.reachable))
        require(
            receiver in 0..15 && receiver !in listOf(4, 5) && result.number in 0..15 &&
                    result.width in listOf(1, 2, 4, 8)
        )
        val initial = State(MutableList(32) { null }, mutableListOf())
        initial.registers[receiver] = Scalar(Value.Receiver, 8)
        val paths = mutableSetOf<Path>()
        var steps = 0
        fun visit(position: Long, state: State, visited: Set<Long>) {
            require(++steps <= 4096 && visited.size < 256 && position !in visited) { "Decision trace exceeds acyclic bound" }
            val instruction = flow.body[position] ?: error("Decision path escapes function")
            fun low(value: Value, width: Int): Value = when (value) {
                is Value.Constant -> Value.Constant(if (width == 8) value.value else value.value and ((1L shl (8 * width)) - 1))
                is Value.Low -> if (width >= value.width) value else Value.Low(value.value, width)
                else -> if (width == 8) value else Value.Low(value, width)
            }

            fun scalar(register: Register): Value {
                val value = state.registers[register.number] ?: error("Unproven decision register ${register.number}")
                require(register.width <= value.width) { "Decision reads unknown upper register bytes" }
                return low(value.value, register.width)
            }

            fun read(operand: Operand?, width: Int): Value = when (operand) {
                is Register -> scalar(operand)
                is Immediate -> low(Value.Constant(operand.value), width)
                is Memory -> {
                    require(
                        !operand.relative && operand.index == null && operand.displacement in 0..4096 &&
                                operand.width in listOf(1, 2, 4, 8)
                    )
                    val pointer = scalar(Register(checkNotNull(operand.base), 8))
                    require(pointer is Value.Returned) { "Decision read is not from a selected call result" }
                    val event = state.events.size
                    state.events += Event.Read(pointer, operand.displacement, operand.width)
                    low(Value.Loaded(event), operand.width)
                }

                else -> error("Unsupported decision operand")
            }

            fun write(operand: Operand?, value: Value, width: Int) {
                val register = operand as? Register ?: error("Decision writes external memory")
                require(register.number !in listOf(4, 5) && register.number in 0..15)
                state.registers[register.number] = Scalar(value, width)
            }
            if (position == end || end == null && instruction.operation == Operation.RET) {
                require(paths.size < 32)
                paths += Path(state.events.toList(), scalar(result))
                return
            }
            val next = instruction.offset + instruction.size
            when (instruction.operation) {
                Operation.MOV, Operation.MOVZX -> {
                    val target = instruction.destination as? Register ?: error("Decision writes external memory")
                    val source = instruction.source
                    if (target.number in listOf(4, 5) && source is Register &&
                        frame.registers(position)[source.number] != null
                    ) {
                        require(target.width == 8 && source.width == 8)
                        state.registers[target.number] = null
                    } else {
                        val width = when (source) {
                            is Register -> source.width
                            is Memory -> source.width
                            else -> target.width
                        }
                        val value = read(source, width)
                        require(target.width >= width)
                        write(target, value, if (target.width == 4) 8 else target.width)
                    }
                }

                Operation.XOR -> {
                    val target = instruction.destination as? Register ?: error("Decision XOR writes memory")
                    val value = if (instruction.source == target) Value.Constant(0) else {
                        val left = scalar(target)
                        val right = read(instruction.source, target.width)
                        if (left is Value.Constant && right is Value.Constant) low(
                            Value.Constant(left.value xor right.value),
                            target.width
                        )
                        else Value.Xor(left, right, target.width)
                    }
                    write(target, value, if (target.width == 4) 8 else target.width)
                    state.comparison = null
                }

                Operation.CMP -> {
                    val width = when (val target = instruction.destination) {
                        is Memory -> target.width
                        is Register -> target.width
                        else -> error("Invalid decision comparison")
                    }
                    state.comparison = read(instruction.destination, width) to read(instruction.source, width)
                }

                Operation.TEST -> {
                    val target = instruction.destination as? Register ?: error("Unsupported decision test")
                    require(instruction.source == target)
                    state.comparison = scalar(target) to Value.Constant(0)
                }

                Operation.SET -> {
                    require(instruction.condition in listOf(4, 5))
                    val comparison = checkNotNull(state.comparison)
                    write(
                        instruction.destination, Value.Equal(
                            comparison.first, comparison.second,
                            instruction.condition == 4
                        ), 1
                    )
                }

                Operation.CALL -> {
                    val target = (instruction.destination as? Immediate)?.value?.plus(address)
                        ?: error("Indirect decision call")
                    val contract = calls[target] ?: error("Unknown decision call")
                    require(contract.isNotEmpty() && contract.all { it.width in listOf(1, 2, 4, 8) })
                    val event = state.events.size
                    state.events += Event.Call(target, contract.map(::scalar))
                    for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)) state.registers[register] = null
                    state.registers[0] = Scalar(Value.Returned(event), 8)
                    state.comparison = null
                }

                Operation.JCC -> {
                    require(instruction.condition in listOf(4, 5)) { "Decision branch is not equality" }
                    val comparison = checkNotNull(state.comparison) { "Decision branch lacks comparison" }
                    val target = (instruction.destination as? Immediate)?.value ?: error("Indirect decision branch")
                    require(target in flow.body && next in flow.body && target != next)
                    for (equal in listOf(false, true)) {
                        val branch = state.copyState()
                        branch.events += Event.Equal(comparison.first, comparison.second, equal)
                        branch.comparison = null
                        visit(if (equal == (instruction.condition == 4)) target else next, branch, visited + position)
                    }
                    return
                }

                Operation.JMP -> {
                    val target = (instruction.destination as? Immediate)?.value ?: error("Indirect decision jump")
                    visit(target, state, visited + position)
                    return
                }

                Operation.PUSH -> require(instruction.destination is Register)
                Operation.POP -> {
                    val target = instruction.destination as? Register ?: error("Invalid decision pop")
                    state.registers[target.number] = null
                }

                Operation.ADD, Operation.SUB -> {
                    require(instruction.destination == Register(4, 8) && instruction.source is Immediate)
                    state.comparison = null
                }

                Operation.NOP, Operation.ENDBR -> Unit
                else -> error("Unsupported scalar decision operation ${instruction.operation}")
            }
            visit(next, state, visited + position)
        }
        visit(start, initial, emptySet())
        require(paths.isNotEmpty())
        return paths
    }
}
