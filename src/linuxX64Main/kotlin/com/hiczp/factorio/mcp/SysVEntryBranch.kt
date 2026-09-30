package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A hypothetical argument value used to inspect a native function's selected path without executing it. */
internal data class ArgumentScalar(val register: Int, val offset: Long, val width: Int, val value: Long) {
    init {
        require(register in setOf(7, 6, 2, 1, 8, 9) && offset in 0..4096 && width in setOf(1, 2, 4, 8))
        require(width == 8 || value >= 0 && value < (1L shl (width * 8)))
    }
}

/** Specializes only the first branch after a bounded, call-free entry prefix with stack-local writes. */
internal object SysVEntryBranch {
    data class Proof(val branch: Long, val successor: Long)
    private sealed interface Value
    private data class Input(val number: Int) : Value
    private data class Stack(val offset: Long) : Value
    private data class Constant(val value: Long) : Value
    private data object Unknown : Value

    fun resolve(bytes: BinaryView, argument: ArgumentScalar): Proof {
        require(bytes.size in 1..8192)
        val decoder = X64Instructions(bytes)
        val registers = MutableList<Value>(16) { Input(it) }
        registers[4] = Stack(0)
        val saved = mutableMapOf<Pair<Long, Int>, Value>()
        var comparison: Pair<ULong, ULong>? = null
        fun top() = (registers[4] as? Stack)?.offset ?: error("Unknown specialized entry frame")
        fun mask(value: Long, width: Int) = if (width == 8) value else value and ((1L shl (width * 8)) - 1)
        fun slot(memory: Memory): Long {
            require(!memory.relative && memory.index == null)
            val base =
                memory.base?.let { registers[it] } as? Stack ?: error("Specialized entry writes outside its frame")
            val offset = base.offset + memory.displacement
            require(offset >= top() && offset <= -memory.width)
            return offset
        }

        fun read(operand: Operand?): Value = when (operand) {
            is Immediate -> Constant(operand.value)
            is Register -> when (val value = registers[operand.number]) {
                is Constant -> Constant(mask(value.value, operand.width))
                else -> if (operand.width == 8) value else Unknown
            }

            is Memory -> {
                require(!operand.relative && operand.index == null)
                when (val base = operand.base?.let { registers[it] }) {
                    is Stack -> saved[slot(operand) to operand.width] ?: Unknown
                    is Input -> if (base.number == argument.register && operand.displacement == argument.offset &&
                        operand.width == argument.width
                    ) Constant(argument.value) else Unknown

                    else -> Unknown
                }
            }

            else -> Unknown
        }

        fun write(target: Operand?, value: Value) {
            when (target) {
                is Register -> {
                    if (target.number == 4) require(target.width == 8 && value is Stack)
                    registers[target.number] = when {
                        target.width == 8 -> value
                        target.width == 4 && value is Constant -> Constant(mask(value.value, 4))
                        else -> Unknown
                    }
                }

                is Memory -> {
                    val offset = slot(target)
                    saved.keys.removeAll { (start, width) -> start < offset + target.width && offset < start + width }
                    saved[offset to target.width] = value
                }

                else -> error("Unsupported specialized entry destination")
            }
        }

        var position = 0L
        var count = 0
        while (position < bytes.size && position < 512) {
            require(++count <= 128) { "Specialized entry exceeds instruction bound" }
            val instruction = decoder.decode(position)
            position += instruction.size
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    val register = instruction.destination as? Register ?: error("Unsupported entry push")
                    require(register.width == 8)
                    val value = read(register)
                    val next = top() - 8
                    require(next >= -16384)
                    registers[4] = Stack(next)
                    saved[next to 8] = value
                }

                Operation.MOV, Operation.MOVZX -> write(instruction.destination, read(instruction.source))
                Operation.LEA -> {
                    val target = instruction.destination as? Register ?: error("Unsupported entry LEA")
                    val source = instruction.source as? Memory ?: error("Unsupported entry address")
                    require(target.width == 8 && target.number != 4 && !source.relative && source.index == null)
                    val base =
                        source.base?.let { registers[it] } as? Stack ?: error("Entry LEA does not name its frame")
                    val offset = base.offset + source.displacement
                    require(offset in top()..0)
                    write(target, Stack(offset))
                }

                Operation.ADD, Operation.SUB -> {
                    require(instruction.destination == Register(4, 8)) { "Unsupported arithmetic in specialized entry" }
                    val amount = (instruction.source as? Immediate)?.value ?: error("Dynamic specialized entry frame")
                    require(amount in 0..16384 && amount % 8 == 0L)
                    val next = top() + if (instruction.operation == Operation.ADD) amount else -amount
                    require(next in -16384..0)
                    registers[4] = Stack(next)
                    saved.keys.removeAll { it.first < next }
                    comparison = null
                }

                Operation.XOR -> {
                    val target = instruction.destination as? Register ?: error("Specialized XOR writes memory")
                    require(target == instruction.source && target.number != 4 && target.width in setOf(4, 8))
                    write(target, Constant(0))
                    comparison = null
                }

                Operation.CMP -> {
                    val width = when (val destination = instruction.destination) {
                        is Register -> destination.width
                        is Memory -> destination.width
                        else -> error("Missing specialized comparison operand")
                    }
                    val left = read(instruction.destination) as? Constant
                    val right = read(instruction.source) as? Constant
                    comparison = if (left != null && right != null)
                        mask(left.value, width).toULong() to mask(right.value, width).toULong() else null
                }

                Operation.JCC -> {
                    val (left, right) = checkNotNull(comparison) { "First branch is not determined by the supplied argument" }
                    val taken = when (instruction.condition) {
                        2 -> left < right
                        3 -> left >= right
                        4 -> left == right
                        5 -> left != right
                        6 -> left <= right
                        7 -> left > right
                        else -> error("Unsupported specialized branch condition")
                    }
                    val target = (instruction.destination as? Immediate)?.value ?: error("Indirect specialized branch")
                    require(target in 0 until bytes.size && position < bytes.size)
                    return Proof(instruction.offset, if (taken) target else position)
                }

                else -> error("Unsupported instruction before specialized branch: ${instruction.operation}")
            }
        }
        error("No determined entry branch within analysis bound")
    }
}
