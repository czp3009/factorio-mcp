package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Native protected-call userdata fields, derived from its dispatch callback. */
internal data class LuaCallRecord(val function: Long, val results: Long) {
    init {
        require(
            function in 0..4096 && results in 0..4096 &&
                    (function + 8 <= results || results + 4 <= function)
        )
    }
}

internal object SysVLuaCallRecord {
    private sealed interface Value
    private data class Input(val register: Int) : Value
    private data class Stack(val offset: Long) : Value
    private data class Member(val offset: Long, val width: Int) : Value
    private data class Constant(val value: Long) : Value
    private data object Unknown : Value

    fun resolve(image: ElfImage): LuaCallRecord {
        val callback = image.symbol("_ZL6f_callP9lua_StatePv")
        val dispatch = image.symbol("_Z9luaD_callP9lua_StateP10lua_TValueii")
        val frames = EhFrames(image)
        frames.function(callback)
        frames.function(dispatch)
        return analyze(image.functionBytes(callback, 512), callback.address, dispatch.address)
    }

    fun analyze(bytes: BinaryView, address: Long, dispatch: Long): LuaCallRecord {
        require(bytes.size in 1..512 && address >= 0 && address <= Long.MAX_VALUE - bytes.size && dispatch >= 0)
        val registers = MutableList<Value>(16) { Input(it) }
        registers[4] = Stack(0)
        val frame = mutableMapOf<Long, Value>()
        var record: LuaCallRecord? = null
        fun top() = (registers[4] as? Stack)?.offset ?: error("Lua call callback has no proven frame")
        fun read(source: X64Instructions.Operand?): Value = when (source) {
            is Register -> {
                val value = registers[source.number]
                require(
                    source.width == 8 || source.width == 4 &&
                            (value is Member && value.width == 4 || value is Constant)
                ) { "Lua callback truncates a pointer" }
                value
            }

            is Immediate -> Constant(source.value)
            is Memory -> {
                require(
                    record == null && !source.relative && source.index == null && source.width in listOf(4, 8) &&
                        source.base?.let { registers[it] } == Input(6) && source.displacement in 0..4096)
                Member(source.displacement, source.width)
            }

            else -> error("Unsupported Lua callback operand")
        }

        fun write(target: X64Instructions.Operand?, value: Value) {
            val register = target as? Register ?: error("Lua call callback writes memory")
            require(
                register.number != 4 && (register.width == 8 || register.width == 4 &&
                        (value is Member && value.width == 4 || value is Constant))
            )
            registers[register.number] = value
        }

        fun restored() = top() == 0L && listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Input(it) }
        val decoder = X64Instructions(bytes)
        var position = 0L
        repeat(64) {
            require(position < bytes.size)
            val instruction = decoder.decode(position)
            position += instruction.size
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    require(record == null && instruction.destination is Register && instruction.destination.width == 8)
                    val value = read(instruction.destination)
                    val next = top() - 8
                    require(next >= -16384)
                    registers[4] = Stack(next)
                    require(frame.put(next, value) == null)
                }

                Operation.POP -> {
                    val target = instruction.destination as? Register ?: error("Invalid Lua callback pop")
                    require(target.width == 8 && target.number != 4)
                    val offset = top()
                    registers[target.number] =
                        frame.remove(offset) ?: error("Lua callback restores an unsaved register")
                    registers[4] = Stack(offset + 8)
                }

                Operation.ADD, Operation.SUB -> {
                    require(instruction.destination == Register(4, 8))
                    val amount = (instruction.source as? Immediate)?.value ?: error("Variable Lua callback frame")
                    require(amount in 0..16384 && amount % 8 == 0L)
                    val next = top() + if (instruction.operation == Operation.ADD) amount else -amount
                    require(next in -16384..0)
                    registers[4] = Stack(next)
                    frame.keys.removeAll { it < next }
                }

                Operation.MOV -> write(instruction.destination, read(instruction.source))
                Operation.XOR -> {
                    require(
                        instruction.destination is Register && instruction.destination.width in listOf(4, 8) &&
                                instruction.destination == instruction.source
                    )
                    write(instruction.destination, Constant(0))
                }

                Operation.CALL, Operation.JMP -> {
                    val relative =
                        (instruction.destination as? Immediate)?.value ?: error("Indirect Lua callback dispatch")
                    require(
                        record == null && relative >= -address && relative <= Long.MAX_VALUE - address &&
                                address + relative == dispatch && registers[7] == Input(7) && registers[1] == Constant(0)
                    ) {
                        "Lua callback changes state, dispatch target or yield argument"
                    }
                    val function = registers[6] as? Member ?: error("Lua callback lacks its function argument")
                    val results = registers[2] as? Member ?: error("Lua callback lacks its result-count argument")
                    require(function.width == 8 && results.width == 4)
                    val resolved = LuaCallRecord(function.offset, results.offset)
                    if (instruction.operation == Operation.JMP) {
                        require(restored()) { "Lua callback tail dispatch has not restored its frame" }
                        return resolved
                    }
                    require((8 + top()) % 16 == 0L)
                    record = resolved
                    for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) registers[register] = Unknown
                }

                Operation.RET -> {
                    require(restored()) { "Lua callback has not restored its frame" }
                    return checkNotNull(record) { "Lua callback returns without dispatch" }
                }

                else -> error("Unsupported Lua call callback operation: ${instruction.operation}")
            }
        }
        error("Lua call callback exceeds instruction bound")
    }
}
