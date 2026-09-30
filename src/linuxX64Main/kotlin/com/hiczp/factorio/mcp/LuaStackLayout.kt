package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Derived from lua_absindex(state, -1); callers must independently establish object and memory bounds. */
internal data class LuaStackLayout(val top: Long, val callInfo: Long, val function: Long, val valueSize: Long) {
    init {
        require(top >= 0 && callInfo >= 0 && function >= 0 && valueSize in 8..128 && valueSize and (valueSize - 1) == 0L)
    }

    fun withinObjects(stateSize: Long, callInfoSize: Long): LuaStackLayout {
        require(
            stateSize >= 8 && top in 0..stateSize - 8 && callInfo in 0..stateSize - 8 &&
                    callInfoSize >= 8 && function in 0..callInfoSize - 8
        ) { "Lua stack fields exceed their object bounds" }
        require(top + 8 <= callInfo || callInfo + 8 <= top) { "Lua stack fields overlap" }
        return this
    }

    fun count(topAddress: ULong, functionAddress: ULong): Int {
        require(topAddress >= functionAddress && topAddress - functionAddress >= valueSize.toULong()) {
            "Lua stack top precedes its frame"
        }
        val distance = topAddress - functionAddress
        require(distance % valueSize.toULong() == 0uL) { "Lua stack pointers are not value-aligned" }
        val count = distance / valueSize.toULong() - 1uL
        require(count <= Int.MAX_VALUE.toULong()) { "Lua stack count exceeds its integer return width" }
        return count.toInt()
    }
}

/** Specializes the complete leaf function for index -1, proving every executed load and the integer return. */
internal object SysVLuaStack {
    private sealed interface Value
    private data object State : Value
    private data class Original(val register: Int) : Value
    private data class Stack(val offset: Long) : Value
    private data class Constant(val bits: ULong) : Value
    private data class Load(val owner: Value, val offset: Long) : Value
    private data class Difference(val top: Load, val base: Load) : Value
    private data class Count(val difference: Difference, val shift: Int, val adjustment: Long = 0) : Value
    private data class Flags(val zero: Boolean, val carry: Boolean, val sign: Boolean, val overflow: Boolean)

    fun resolve(image: ElfImage, name: String = "lua_absindex"): LuaStackLayout {
        val symbol = image.symbol(name)
        require(symbol.size in 1..256) { "Lua stack accessor exceeds analysis bound" }
        EhFrames(image).function(symbol)
        return analyze(image.functionBytes(symbol, 256))
    }

    fun analyze(bytes: BinaryView): LuaStackLayout {
        require(bytes.size in 1..256)
        val instructions = X64Instructions(bytes).all().associateBy { it.offset }
        val registers = (0..15).associateWith<Int, Value> { Original(it) }.toMutableMap()
        registers[7] = State
        registers[6] = Constant(UInt.MAX_VALUE.toULong())
        registers[4] = Stack(0)
        val stack = mutableMapOf<Long, Value>()
        val loads = mutableSetOf<Load>()
        var flags: Flags? = null
        fun mask(width: Int) = if (width == 8) ULong.MAX_VALUE else (1uL shl (width * 8)) - 1uL
        fun read(register: Register): Value {
            val value = registers.getValue(register.number)
            require(register.width == 8 || register.width == 4 && (value is Constant || value is Count)) {
                "Lua stack accessor truncates pointer or unknown register provenance"
            }
            return if (value is Constant) Constant(value.bits and mask(register.width)) else value
        }

        fun operand(value: X64Instructions.Operand?, width: Int): Value = when (value) {
            is Register -> read(value)
            is Immediate -> Constant(value.value.toULong() and mask(width))
            is Memory -> {
                require(
                    value.width == 8 && !value.relative && value.index == null && value.base != null &&
                            value.displacement in 0..4096
                ) { "Unsupported Lua stack load" }
                val owner = registers.getValue(value.base)
                require(owner == State || owner is Load && owner.owner == State) { "Unknown Lua stack load provenance" }
                Load(owner, value.displacement).also { loads += it }
            }

            else -> error("Unsupported Lua stack operand")
        }

        fun write(register: Register, value: Value) {
            require(register.width == 8 || register.width == 4 && (value is Constant || value is Count)) {
                "Unsupported partial Lua stack register write"
            }
            registers[register.number] = if (value is Constant) Constant(value.bits and mask(register.width)) else value
        }

        fun compare(left: ULong, right: ULong, width: Int) {
            val bit = 1uL shl (width * 8 - 1)
            val result = (left - right) and mask(width)
            flags = Flags(
                result == 0uL, left < right, result and bit != 0uL,
                (left xor right) and (left xor result) and bit != 0uL
            )
        }

        val visited = mutableSetOf<Long>()
        var position = 0L
        while (true) {
            require(visited.add(position)) { "Lua stack accessor contains an executed loop" }
            val instruction = instructions[position] ?: error("Lua stack branch is outside an instruction boundary")
            position += instruction.size
            val target = instruction.destination
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    val source = target as? Register ?: error("Unsupported Lua stack frame push")
                    require(source.width == 8)
                    val next = (registers[4] as? Stack ?: error("Unknown Lua stack frame")).offset - 8
                    require(next in -128..-8)
                    stack[next] = read(source)
                    registers[4] = Stack(next)
                }

                Operation.POP -> {
                    val destination = target as? Register ?: error("Unsupported Lua stack frame pop")
                    require(destination.width == 8 && destination.number != 4)
                    val offset = (registers[4] as? Stack ?: error("Unknown Lua stack frame")).offset
                    registers[destination.number] = stack.remove(offset) ?: error("Unproven Lua stack frame value")
                    registers[4] = Stack(offset + 8)
                }

                Operation.MOV -> {
                    val destination = target as? Register ?: error("Lua stack accessor writes memory")
                    write(destination, operand(instruction.source, destination.width))
                }

                Operation.LEA -> {
                    val destination = target as? Register ?: error("Unsupported Lua stack address target")
                    val source = instruction.source as? Memory ?: error("Unsupported Lua stack address")
                    require(!source.relative && source.index == null && source.base != null)
                    val value = registers[source.base] as? Constant ?: error("Nonconstant Lua stack index adjustment")
                    write(destination, Constant(value.bits + source.displacement.toULong()))
                }

                Operation.CMP, Operation.TEST -> {
                    val width = when (target) {
                        is Register -> target.width
                        is Memory -> target.width
                        else -> error("Unsupported Lua stack comparison")
                    }
                    val left = operand(target, width) as? Constant
                    val right = operand(instruction.source, width) as? Constant
                    if (left == null || right == null) flags = null
                    else if (instruction.operation == Operation.CMP) compare(left.bits, right.bits, width)
                    else {
                        val bits = left.bits and right.bits
                        flags = Flags(bits == 0uL, false, bits and (1uL shl (width * 8 - 1)) != 0uL, false)
                    }
                }

                Operation.ADD, Operation.SUB -> {
                    val destination = target as? Register ?: error("Lua stack accessor mutates memory")
                    val left = read(destination)
                    val right = operand(instruction.source, destination.width)
                    flags = null
                    val count = (left as? Count) ?: (right as? Count)
                    val constant = (left as? Constant) ?: (right as? Constant)
                    val result = when {
                        instruction.operation == Operation.SUB && destination.width == 8 && left is Load && right is Load ->
                            Difference(left, right)

                        instruction.operation == Operation.ADD && count != null && constant != null -> {
                            val signed =
                                if (destination.width == 4) constant.bits.toInt().toLong() else constant.bits.toLong()
                            require(signed in -128..128 && count.adjustment + signed in -128..128)
                            count.copy(adjustment = count.adjustment + signed)
                        }

                        else -> error("Unsupported Lua stack arithmetic")
                    }
                    write(destination, result)
                }

                Operation.SHR, Operation.SAR -> {
                    val destination = target as? Register ?: error("Unsupported Lua stack division")
                    val difference =
                        read(destination) as? Difference ?: error("Lua stack division lacks pointer difference")
                    val shift = (instruction.source as? Immediate)?.value ?: error("Nonconstant Lua value size")
                    require(destination.width == 8 && shift in 3..7)
                    write(destination, Count(difference, shift.toInt()))
                    flags = null
                }

                Operation.JCC -> {
                    val condition = flags ?: error("Lua stack branch depends on an unknown value")
                    val taken = when (instruction.condition) {
                        2 -> condition.carry
                        3 -> !condition.carry
                        4 -> condition.zero
                        5 -> !condition.zero
                        6 -> condition.carry || condition.zero
                        7 -> !condition.carry && !condition.zero
                        8 -> condition.sign
                        9 -> !condition.sign
                        12 -> condition.sign != condition.overflow
                        13 -> condition.sign == condition.overflow
                        14 -> condition.zero || condition.sign != condition.overflow
                        15 -> !condition.zero && condition.sign == condition.overflow
                        else -> error("Unsupported Lua stack condition")
                    }
                    if (taken) position = (target as? Immediate)?.value ?: error("Indirect Lua stack branch")
                }

                Operation.JMP -> position = (target as? Immediate)?.value ?: error("Indirect Lua stack branch")
                Operation.RET -> {
                    require(
                        registers[4] == Stack(0) && stack.isEmpty() &&
                                listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Original(it) }) {
                        "Lua stack accessor does not restore the System V frame"
                    }
                    val result = registers[0] as? Count ?: error("Lua stack accessor does not return a count")
                    val top = result.difference.top
                    val base = result.difference.base
                    val frame = base.owner as? Load ?: error("Lua stack base is not in the current call frame")
                    require(
                        result.adjustment == -1L && top.owner == State && frame.owner == State &&
                                loads == setOf(top, base, frame)
                    ) { "Lua stack accessor has unexpected loads or count semantics" }
                    return LuaStackLayout(top.offset, frame.offset, base.offset, 1L shl result.shift)
                }

                else -> error("Unsupported Lua stack operation: ${instruction.operation}")
            }
        }
    }
}
