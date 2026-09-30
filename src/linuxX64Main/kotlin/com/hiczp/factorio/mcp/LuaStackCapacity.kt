package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Capacity member from the number-push entry guard; invocation ABI is verified separately. */
internal object SysVLuaCapacity {
    data class Guard(val capacity: Long, val branch: Long, val available: Long)
    private sealed interface Value
    private data object State : Value
    private data class Original(val register: Int) : Value
    private data class Stack(val offset: Long) : Value
    private data class Member(val offset: Long) : Value
    private data class Difference(val end: Member, val top: Member) : Value

    fun resolve(image: ElfImage, stack: LuaStackLayout, stateSize: Long): Long {
        val symbol = image.symbol("lua_pushnumber")
        EhFrames(image).function(symbol)
        return analyze(image.functionBytes(symbol, 1024), stack, stateSize)
    }

    fun analyze(bytes: BinaryView, stack: LuaStackLayout, stateSize: Long): Long {
        return guard(bytes, stack, stateSize).capacity
    }

    fun guard(bytes: BinaryView, stack: LuaStackLayout, stateSize: Long): Guard {
        require(bytes.size in 1..1024 && stateSize >= 8 && stack.top in 0..stateSize - 8)
        val registers = MutableList<Value>(16) { Original(it) }
        registers[7] = State
        registers[4] = Stack(0)
        val decoder = X64Instructions(bytes)
        var position = 0L
        var compared: Difference? = null
        fun top() = (registers[4] as? Stack)?.offset ?: error("Unknown Lua capacity frame")
        fun value(operand: X64Instructions.Operand?): Value = when (operand) {
            is Register -> {
                require(operand.width == 8) { "Lua capacity truncates an address" }
                registers[operand.number]
            }

            is Memory -> {
                require(
                    operand.width == 8 && !operand.relative && operand.index == null &&
                        operand.base?.let { registers[it] } == State && operand.displacement in 0..stateSize - 8) {
                    "Lua capacity load is not a bounded state pointer member"
                }
                Member(operand.displacement)
            }

            else -> error("Unsupported Lua capacity source")
        }
        repeat(64) {
            require(position < bytes.size && position < 256)
            val instruction = decoder.decode(position)
            position += instruction.size
            val previous = compared
            compared = null
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    require(instruction.destination is Register && instruction.destination.width == 8)
                    val next = top() - 8
                    require(next >= -16384)
                    registers[4] = Stack(next)
                }

                Operation.MOV -> when (val target = instruction.destination) {
                    is Register -> {
                        require(target.number != 4)
                        registers[target.number] = if (instruction.source is Immediate) {
                            require(target.width in listOf(4, 8))
                            Original(target.number) // Constants cannot become pointer evidence.
                        } else {
                            require(target.width == 8)
                            value(instruction.source)
                        }
                    }

                    is Memory -> {
                        require(!target.relative && target.index == null && instruction.source is Immediate)
                        val base =
                            target.base?.let { registers[it] } as? Stack ?: error("Lua capacity writes game memory")
                        val offset = base.offset + target.displacement
                        require(offset >= top() && offset <= -target.width)
                    }

                    else -> error("Unsupported Lua capacity move")
                }

                Operation.SUB -> {
                    val target = instruction.destination as? Register ?: error("Lua capacity modifies memory")
                    require(target.width == 8)
                    if (target.number == 4) {
                        val amount = (instruction.source as? Immediate)?.value ?: error("Dynamic Lua capacity frame")
                        require(amount in 0..16384 && amount % 8 == 0L && top() - amount >= -16384)
                        registers[4] = Stack(top() - amount)
                    } else {
                        val end = value(target) as? Member ?: error("Lua capacity lacks a state end member")
                        val start =
                            value(instruction.source) as? Member ?: error("Lua capacity lacks a state top member")
                        require(start.offset == stack.top && end.offset != start.offset)
                        registers[target.number] = Difference(end, start)
                    }
                }

                Operation.CMP -> {
                    val difference =
                        value(instruction.destination) as? Difference ?: error("Lua capacity compares no difference")
                    require((instruction.source as? Immediate)?.value == stack.valueSize)
                    compared = difference
                }

                Operation.JCC -> {
                    val difference = checkNotNull(previous) { "Lua capacity branch lost comparison flags" }
                    val destination =
                        (instruction.destination as? Immediate)?.value ?: error("Indirect Lua capacity branch")
                    require(instruction.condition == 14 && destination in position until bytes.size && registers[7] == State)
                    require(
                        difference.end.offset % 8 == 0L &&
                                (difference.end.offset + 8 <= stack.top || stack.top + 8 <= difference.end.offset)
                    )
                    return Guard(difference.end.offset, instruction.offset, position)
                }

                else -> error("Unsupported operation before Lua capacity guard: ${instruction.operation}")
            }
        }
        error("Lua capacity guard exceeds instruction bound")
    }
}
