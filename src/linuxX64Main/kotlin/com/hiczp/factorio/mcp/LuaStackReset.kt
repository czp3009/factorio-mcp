package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Proves settop(state, 0) for a well-formed frame: function + stride <= top <= stack end. */
internal object SysVLuaStackReset {
    private sealed interface Value
    private data class Original(val register: Int) : Value
    private data class Stack(val offset: Long) : Value
    private data class Constant(val bits: Long) : Value
    private data class Capacity(val shift: Int = 0) : Value
    private enum class Pointer : Value { STATE, FRAME, FUNCTION, BASE, TOP, END }
    private data class Flags(
        val equal: Boolean?,
        val signedLess: Boolean?,
        val unsignedLess: Boolean?,
        val sign: Boolean?
    )

    fun verify(image: ElfImage, layout: LuaStackLayout, capacity: Long, stateSize: Long) {
        val function = image.symbol("lua_settop")
        EhFrames(image).function(function)
        analyze(image.functionBytes(function, 512), layout, capacity, stateSize)
    }

    fun analyze(bytes: BinaryView, layout: LuaStackLayout, capacity: Long, stateSize: Long) {
        val members = listOf(layout.top, layout.callInfo, capacity).sorted()
        require(bytes.size in 1..512 && stateSize >= 8 && members.all { it in 0..stateSize - 8 } &&
                members.zipWithNext().all { (first, second) -> second - first >= 8 })
        val registers = MutableList<Value>(16) { Original(it) }
        registers[7] = Pointer.STATE
        registers[6] = Constant(0)
        registers[4] = Stack(0)
        val frame = mutableMapOf<Long, Value>()
        val loads = mutableSetOf<Pointer>()
        var flags: Flags? = null
        var stores = 0
        fun top() = (registers[4] as? Stack)?.offset ?: error("Unknown Lua reset frame")
        fun read(source: X64Instructions.Operand?): Value = when (source) {
            is Register -> {
                val value = registers[source.number]
                require(source.width == 8 || source.width == 4 && value is Constant) { "Lua reset truncates a pointer" }
                if (source.width == 4 && value is Constant) Constant(value.bits and 0xffffffffL) else value
            }

            is Immediate -> Constant(source.value)
            is Memory -> {
                require(source.width == 8 && !source.relative && source.index == null)
                val value = when (source.base?.let { registers[it] }) {
                    Pointer.STATE -> when (source.displacement) {
                        layout.top -> Pointer.TOP
                        layout.callInfo -> Pointer.FRAME
                        capacity -> Pointer.END
                        else -> error("Lua reset reads an unexpected state member")
                    }

                    Pointer.FRAME -> {
                        require(source.displacement == layout.function)
                        Pointer.FUNCTION
                    }

                    else -> error("Lua reset reads through an unknown receiver")
                }
                loads += value
                value
            }

            else -> error("Unsupported Lua reset operand")
        }

        fun write(target: Register, value: Value) {
            require(target.width == 8 || target.width == 4 && value is Constant)
            if (target.number == 4) require(value is Stack)
            registers[target.number] = if (target.width == 4 && value is Constant)
                Constant(value.bits and 0xffffffffL) else value
        }

        val decoder = X64Instructions(bytes)
        val visited = mutableSetOf<Long>()
        var position = 0L
        repeat(128) {
            require(position in 0 until bytes.size && visited.add(position)) { "Lua reset loops or leaves the selected function" }
            val instruction = decoder.decode(position)
            position += instruction.size
            val previous = flags
            flags = null
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> flags = previous
                Operation.PUSH -> {
                    val target = instruction.destination as? Register ?: error("Unsupported Lua reset push")
                    require(target.width == 8)
                    val value = read(target)
                    val next = top() - 8
                    require(next >= -1024)
                    registers[4] = Stack(next)
                    frame[next] = value
                    flags = previous
                }

                Operation.POP -> {
                    val target = instruction.destination as? Register ?: error("Unsupported Lua reset pop")
                    require(target.width == 8 && target.number != 4)
                    val offset = top()
                    write(target, frame.remove(offset) ?: error("Unproven Lua reset saved register"))
                    registers[4] = Stack(offset + 8)
                    flags = previous
                }

                Operation.MOV -> {
                    val value = read(instruction.source)
                    when (val target = instruction.destination) {
                        is Register -> write(target, value)
                        is Memory -> {
                            require(
                                target.width == 8 && !target.relative && target.index == null &&
                                    target.base?.let { registers[it] } == Pointer.STATE && target.displacement == layout.top &&
                                    value == Pointer.BASE && ++stores == 1) { "Lua reset has an unexpected write" }
                        }

                        else -> error("Unsupported Lua reset destination")
                    }
                    flags = previous
                }

                Operation.ADD, Operation.SUB -> {
                    val target = instruction.destination as? Register ?: error("Lua reset mutates other memory")
                    val left = read(target)
                    val right = read(instruction.source)
                    val value = when {
                        instruction.operation == Operation.ADD && left == Pointer.FUNCTION && right == Constant(layout.valueSize) -> Pointer.BASE
                        instruction.operation == Operation.ADD && left == Pointer.BASE && right == Constant(0) -> Pointer.BASE
                        instruction.operation == Operation.SUB && left == Pointer.END && right == Pointer.BASE -> Capacity()
                        instruction.operation == Operation.ADD && left is Constant && right is Constant -> Constant(left.bits + right.bits)
                        else -> error("Unexpected Lua reset arithmetic")
                    }
                    write(target, value)
                }

                Operation.SAR, Operation.SHL -> {
                    val target = instruction.destination as? Register ?: error("Lua reset shifts memory")
                    val shift = (instruction.source as? Immediate)?.value ?: error("Dynamic Lua reset shift")
                    require(shift in 3..7 && 1L shl shift.toInt() == layout.valueSize)
                    val value = when (val source = read(target)) {
                        is Capacity -> {
                            require(instruction.operation == Operation.SAR && source.shift == 0 && target.width == 8)
                            source.copy(shift = shift.toInt())
                        }

                        Constant(0) -> Constant(0)
                        else -> error("Lua reset shifts an unknown value")
                    }
                    write(target, value)
                }

                Operation.TEST -> {
                    require(instruction.destination == instruction.source && read(instruction.destination) == Constant(0))
                    flags = Flags(true, false, false, false)
                }

                Operation.CMP -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    flags = when {
                        left is Capacity && left.shift > 0 && right == Constant(0) -> Flags(null, false, false, false)
                        left == Pointer.TOP && right == Pointer.BASE -> Flags(null, false, false, null)
                        left is Constant && right is Constant -> Flags(
                            left.bits == right.bits,
                            left.bits < right.bits,
                            left.bits.toULong() < right.bits.toULong(),
                            left.bits - right.bits < 0
                        )

                        else -> error("Unproven Lua reset comparison")
                    }
                }

                Operation.JCC -> {
                    val destination =
                        (instruction.destination as? Immediate)?.value ?: error("Indirect Lua reset branch")
                    require(destination in 0 until bytes.size) { "Lua reset guard leaves its function" }
                    val condition = checkNotNull(previous) { "Lua reset branch lost its flags" }
                    val taken = when (instruction.condition) {
                        2 -> condition.unsignedLess
                        3 -> condition.unsignedLess?.not()
                        4 -> condition.equal
                        5 -> condition.equal?.not()
                        8 -> condition.sign
                        9 -> condition.sign?.not()
                        12 -> condition.signedLess
                        13 -> condition.signedLess?.not()
                        else -> error("Unsupported Lua reset branch condition")
                    } ?: error("Lua reset condition depends on unproven frame state")
                    if (taken) position = destination
                    flags = previous
                }

                Operation.JMP -> {
                    position = (instruction.destination as? Immediate)?.value ?: error("Indirect Lua reset branch")
                    flags = previous
                }

                Operation.RET -> {
                    require(
                        stores == 1 && top() == 0L && frame.isEmpty() &&
                            listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Original(it) } &&
                            loads == setOf(Pointer.FRAME, Pointer.FUNCTION, Pointer.END, Pointer.TOP)) {
                        "Lua reset does not preserve its frame or has unexpected reads"
                    }
                    return
                }

                else -> error("Unsupported Lua reset operation: ${instruction.operation}")
            }
        }
        error("Lua reset exceeds instruction bound")
    }
}
