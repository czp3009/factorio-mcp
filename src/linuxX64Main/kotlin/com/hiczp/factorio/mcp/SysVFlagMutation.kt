package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Derives a flag from the first receiver write on every reachable setter prefix, without calling it. */
internal object SysVFlagMutation {
    private sealed interface Value
    private data class Receiver(val offset: Long = 0) : Value
    private data class Stack(val offset: Long) : Value
    private data class Constant(val bits: ULong) : Value
    private data class Field(val offset: Long, val width: Int, val keep: ULong, val set: ULong = 0u) : Value
    private data object Unknown : Value
    private data class State(
        val registers: MutableList<Value>,
        val saved: MutableMap<Pair<Long, Int>, Value> = mutableMapOf(),
        var zero: Boolean? = null,
    ) {
        fun copyState() = State(registers.toMutableList(), saved.toMutableMap(), zero)
    }

    fun resolveBoolean(image: ElfImage, name: String, objectSize: Long): NativeAccessor {
        val symbol = image.symbol(name)
        EhFrames(image).function(symbol)
        val bytes = image.functionBytes(symbol, 4096)
        val clear = analyze(bytes, objectSize, false, booleanArgument = false)
        val set = analyze(bytes, objectSize, true, booleanArgument = true)
        require(clear == set) { "Boolean setter branches change different fields" }
        return set
    }

    fun resolveConstant(image: ElfImage, name: String, objectSize: Long, set: Boolean): NativeAccessor {
        val symbol = image.symbol(name)
        EhFrames(image).function(symbol)
        return analyze(image.functionBytes(symbol, 4096), objectSize, set)
    }

    /** Unknown conditions explore both arms. Each admitted write must set/clear the same single bit. */
    fun analyze(bytes: BinaryView, objectSize: Long, set: Boolean, booleanArgument: Boolean? = null): NativeAccessor {
        require(bytes.size in 1..4096 && objectSize in 1..(16 * 1024 * 1024))
        val initial = State(MutableList(16) { Unknown })
        initial.registers[7] = Receiver()
        initial.registers[4] = Stack(0)
        booleanArgument?.let { initial.registers[6] = Constant(if (it) 1u else 0u) }
        val pending = ArrayDeque<Pair<Long, State>>()
        pending.add(0L to initial)
        val decoded = mutableMapOf<Long, X64Instructions.Instruction>()
        val writes = mutableSetOf<NativeAccessor>()
        val decoder = X64Instructions(bytes)
        var work = 0
        fun mask(width: Int) = if (width == 8) ULong.MAX_VALUE else (1uL shl (width * 8)) - 1u
        while (pending.isNotEmpty()) {
            var (position, state) = pending.removeLast()
            fun location(memory: Memory): Value {
                require(!memory.relative && memory.index == null) { "Flag access has an unproven address" }
                return when (val base = memory.base?.let { state.registers[it] }) {
                    is Receiver -> Receiver(base.offset + memory.displacement).also {
                        require(it.offset in 0..objectSize - memory.width) { "Flag access exceeds object bounds" }
                    }

                    is Stack -> Stack(base.offset + memory.displacement).also {
                        val top = state.registers[4] as? Stack ?: error("Unknown setter frame")
                        require(it.offset >= top.offset && it.offset <= -memory.width)
                    }

                    else -> error("Flag access does not use the original receiver or local frame")
                }
            }

            fun read(operand: X64Instructions.Operand?): Value = when (operand) {
                is Immediate -> Constant(operand.value.toULong())
                is Register -> when (val value = state.registers[operand.number]) {
                    is Constant -> Constant(value.bits and mask(operand.width))
                    is Field -> if (operand.width >= value.width) value else Unknown
                    is Receiver, is Stack -> value.also { require(operand.width == 8) }
                    Unknown -> Unknown
                }

                is Memory -> when (val at = location(operand)) {
                    is Receiver -> Field(at.offset, operand.width, mask(operand.width))
                    is Stack -> state.saved[at.offset to operand.width] ?: Unknown
                    else -> error("Unsupported flag memory")
                }

                else -> error("Unsupported flag operand")
            }

            fun writeRegister(target: Register, value: Value) {
                require(target.number in 0..15)
                require(target.width == 8 || value !is Receiver && value !is Stack)
                if (target.number == 4) require(target.width == 8 && value is Stack)
                state.registers[target.number] = when {
                    target.width < 4 -> Unknown // No proof of unaffected upper bits.
                    value is Constant -> Constant(value.bits and mask(target.width))
                    value is Field && target.width < value.width -> Unknown
                    else -> value
                }
            }

            fun store(target: Memory, value: Value): Boolean {
                when (val at = location(target)) {
                    is Stack -> {
                        state.saved.keys.removeAll { (offset, width) -> offset < at.offset + target.width && at.offset < offset + width }
                        state.saved[at.offset to target.width] = value
                        return false
                    }

                    is Receiver -> {
                        require(value is Field && value.offset == at.offset && value.width == target.width && target.width in 1..4) {
                            "Setter does not preserve the original flag field"
                        }
                        val full = mask(value.width)
                        val changed = if (set) value.set else full xor value.keep
                        require(
                            changed != 0uL && changed and (changed - 1u) == 0uL &&
                                    if (set) value.keep == full else value.set == 0uL
                        ) {
                            "Setter changes more than the requested single flag bit"
                        }
                        // x86 is little-endian. Narrow only after proving the complete native read/modify/write.
                        val bit = changed.countTrailingZeroBits()
                        writes += NativeAccessor(at.offset + bit / 8, 1, 1uL shl (bit % 8), bit % 8)
                        return true
                    }

                    else -> error("Unsupported setter destination")
                }
            }

            fun write(target: X64Instructions.Operand?, value: Value): Boolean = when (target) {
                is Register -> {
                    writeRegister(target, value); false
                }

                is Memory -> store(target, value)
                else -> error("Unsupported setter destination")
            }
            path@ while (true) {
                require(++work <= 1024 && position in 0 until bytes.size && position < 1024) { "Flag prefix exceeds analysis bound" }
                val instruction = decoded.getOrPut(position) {
                    decoder.decode(position).also { next ->
                        require(decoded.values.none { it.offset < position + next.size && position < it.offset + it.size }) {
                            "Flag branch enters the middle of an instruction"
                        }
                    }
                }
                position += instruction.size
                when (instruction.operation) {
                    Operation.NOP, Operation.ENDBR -> Unit
                    Operation.PUSH -> {
                        val value = read(instruction.destination)
                        val top = state.registers[4] as? Stack ?: error("Unknown setter frame")
                        require(top.offset >= -4096)
                        state.registers[4] = Stack(top.offset - 8)
                        state.saved[top.offset - 8 to 8] = value
                    }

                    Operation.POP -> {
                        val target = instruction.destination as? Register ?: error("Unknown setter pop")
                        val top = state.registers[4] as? Stack ?: error("Unknown setter frame")
                        require(top.offset < 0 && target != Register(4, 8))
                        writeRegister(target, state.saved.remove(top.offset to 8) ?: Unknown)
                        state.registers[4] = Stack(top.offset + 8)
                    }

                    Operation.MOV, Operation.MOVZX -> if (write(
                            instruction.destination,
                            read(instruction.source)
                        )
                    ) break@path

                    Operation.AND, Operation.OR, Operation.XOR -> {
                        val value = read(instruction.destination)
                        val constant =
                            (read(instruction.source) as? Constant)?.bits ?: error("Flag transform is not constant")
                        val result = when (value) {
                            is Constant -> Constant(
                                when (instruction.operation) {
                                    Operation.AND -> value.bits and constant
                                    Operation.OR -> value.bits or constant
                                    else -> value.bits xor constant
                                }
                            )

                            is Field -> {
                                val bits = constant and mask(value.width)
                                when (instruction.operation) {
                                    Operation.AND -> value.copy(keep = value.keep and bits, set = value.set and bits)
                                    Operation.OR -> value.copy(set = value.set or bits)
                                    else -> error("Flag toggling does not identify a setter")
                                }
                            }

                            else -> error("Unproven flag transform")
                        }
                        val width = when (val target = instruction.destination) {
                            is Register -> target.width
                            is Memory -> target.width
                            else -> error("Invalid flag transform destination")
                        }
                        state.zero = (result as? Constant)?.let { it.bits and mask(width) == 0uL }
                        if (write(instruction.destination, result)) break@path
                    }

                    Operation.ADD, Operation.SUB -> {
                        val target = instruction.destination as? Register ?: error("Setter changes unknown memory")
                        require(target == Register(4, 8))
                        val top = state.registers[4] as? Stack ?: error("Unknown setter frame")
                        val amount = (instruction.source as? Immediate)?.value ?: error("Dynamic setter frame")
                        require(amount in 0..4096 && amount % 8 == 0L)
                        val next = top.offset + if (instruction.operation == Operation.ADD) amount else -amount
                        require(next in -4096..0)
                        state.registers[4] = Stack(next)
                        state.zero = null
                    }

                    Operation.CMP, Operation.TEST -> {
                        val left = read(instruction.destination) as? Constant
                        val right = read(instruction.source) as? Constant
                        state.zero = if (left == null || right == null) null else
                            if (instruction.operation == Operation.CMP) left.bits == right.bits else left.bits and right.bits == 0uL
                    }

                    Operation.SET -> {
                        require(instruction.condition in listOf(4, 5))
                        val value = state.zero?.let { if (instruction.condition == 4) it else !it }
                        val target =
                            instruction.destination as? Register ?: error("Setter writes a condition to memory")
                        writeRegister(target, value?.let { Constant(if (it) 1u else 0u) } ?: Unknown)
                    }

                    Operation.JMP, Operation.JCC -> {
                        val target = (instruction.destination as? Immediate)?.value ?: error("Indirect setter branch")
                        require(target >= position && target < bytes.size && target < 1024) { "Unbounded setter branch" }
                        val taken = if (instruction.operation == Operation.JMP) true else {
                            require(instruction.condition in listOf(4, 5)) { "Unsupported setter condition" }
                            state.zero?.let { if (instruction.condition == 4) it else !it }
                        }
                        if (taken == null) pending.add(target to state.copyState())
                        if (taken == true) position = target
                    }

                    Operation.RET -> break@path
                    else -> error("Unsupported operation before flag assignment: ${instruction.operation}")
                }
            }
        }
        return writes.singleOrNull() ?: error("Setter prefix does not identify one unambiguous flag")
    }
}
