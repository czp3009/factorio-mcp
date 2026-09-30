package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Reader dispatch on the parser's first refill; the caller must cross-check the initialized stream. */
internal data class LuaReaderLayout(
    val stream: Long,
    val count: Long,
    val callback: Long,
    val state: Long,
    val userdata: Long
) {
    init {
        val fields = listOf(count, callback, state, userdata).sorted()
        require(stream in 0..4096 && fields.all { it in 0..4096 } &&
                fields.zipWithNext().all { (left, right) -> right - left >= 8 })
    }
}

internal object SysVLuaReader {
    data class Proof(val layout: LuaReaderLayout, val call: Long, val output: Long)
    private sealed interface Value
    private data class Input(val register: Int) : Value
    private data class Stack(val offset: Long) : Value
    private data class Field(val base: Value, val offset: Long) : Value

    fun resolve(image: ElfImage): LuaReaderLayout {
        val parser = image.symbol("_ZL8f_parserP9lua_StatePv")
        EhFrames(image).function(parser)
        return analyze(image.functionBytes(parser, 8192))
    }

    fun analyze(bytes: BinaryView): LuaReaderLayout {
        return inspect(bytes).layout
    }

    fun inspect(bytes: BinaryView): Proof {
        require(bytes.size in 1..8192)
        val registers = MutableList<Value>(16) { Input(it) }
        registers[4] = Stack(0)
        val frame = mutableMapOf<Long, Value>()
        val pushed = mutableSetOf<Long>()
        var decremented: Field? = null
        var guarded = false
        var branchEnd: Long? = null
        fun top() = (registers[4] as? Stack)?.offset ?: error("Reader has no proven frame")
        fun memory(source: Memory): Value {
            require(source.width == 8 && !source.relative && source.index == null && source.base != null)
            val base = registers[source.base]
            if (base is Stack) {
                val offset = base.offset + source.displacement
                require(offset >= top() && offset <= -8)
                return frame[offset] ?: error("Reader loads an uninitialized frame member")
            }
            require(
                source.displacement in 0..4096 &&
                        (base == Input(6) || base is Field && base.base == Input(6))
            ) { "Reader load has an unproven object" }
            return Field(base, source.displacement)
        }

        fun read(source: X64Instructions.Operand?): Value = when (source) {
            is Register -> {
                require(source.width == 8)
                registers[source.number]
            }

            is Memory -> memory(source)
            else -> error("Unsupported reader value")
        }

        fun write(target: X64Instructions.Operand?, value: Value) {
            when (target) {
                is Register -> {
                    require(target.width == 8 && target.number != 4)
                    registers[target.number] = value
                }

                is Memory -> {
                    require(target.width == 8 && !target.relative && target.index == null)
                    val base = target.base?.let { registers[it] } as? Stack ?: error("Reader prefix writes game memory")
                    val offset = base.offset + target.displacement
                    require(offset >= top() && offset <= -8 && pushed.none { it < offset + 8 && offset < it + 8 } &&
                            frame.keys.none { it < offset + 8 && offset < it + 8 })
                    frame[offset] = value
                }

                else -> error("Unsupported reader destination")
            }
        }

        val decoder = X64Instructions(bytes)
        var position = 0L
        var subtractionFlags = false
        repeat(64) {
            require(position < bytes.size && position < 1024)
            val instruction = decoder.decode(position)
            position += instruction.size
            val previousFlags = subtractionFlags
            subtractionFlags = false
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    val source = instruction.destination as? Register ?: error("Reader push is not a register")
                    val value = read(instruction.destination)
                    val next = top() - 8
                    require(next >= -16384)
                    registers[4] = Stack(next)
                    // Caller-saved pushes can allocate a local output slot instead of saving a register.
                    if (source.number in listOf(3, 5, 12, 13, 14, 15) || value is Stack) {
                        require(frame.put(next, value) == null)
                        pushed += next
                    }
                }

                Operation.MOV -> write(instruction.destination, read(instruction.source))
                Operation.SUB -> {
                    val amount =
                        (instruction.source as? Immediate)?.value ?: error("Reader subtraction is not constant")
                    if (instruction.destination == Register(4, 8)) {
                        require(amount in 0..16384 && amount % 8 == 0L && top() - amount >= -16384)
                        registers[4] = Stack(top() - amount)
                    } else {
                        require(decremented == null && amount == 1L && instruction.destination is Memory)
                        val field =
                            memory(instruction.destination) as? Field ?: error("Reader count is not in its stream")
                        require(field.base is Field && field.base.base == Input(6))
                        decremented = field
                        subtractionFlags = true
                    }
                }

                Operation.JCC -> {
                    val target = (instruction.destination as? Immediate)?.value ?: error("Indirect reader branch")
                    // A zero-initialized count borrows when decremented; refill is the fallthrough edge.
                    require(
                        !guarded && previousFlags && decremented != null && instruction.condition in listOf(2, 3) &&
                                target in position until bytes.size
                    )
                    if (instruction.condition == 3) branchEnd = target else position = target
                    guarded = true
                }

                Operation.LEA -> {
                    val source = instruction.source as? Memory ?: error("Invalid reader output address")
                    require(!source.relative && source.index == null)
                    val base = source.base?.let { registers[it] } as? Stack ?: error("Reader size output is not local")
                    val offset = base.offset + source.displacement
                    require(offset >= top() && offset <= -8 && pushed.none { it < offset + 8 && offset < it + 8 })
                    write(instruction.destination, Stack(offset))
                }

                Operation.CALL -> {
                    require(guarded && (branchEnd == null || position <= checkNotNull(branchEnd)) && (8 + top()) % 16 == 0L)
                    val callback =
                        read(instruction.destination) as? Field ?: error("Reader callback is not a stream member")
                    val stream = callback.base as? Field ?: error("Reader callback lacks parser userdata")
                    val state = registers[7] as? Field ?: error("Reader state is not a stream member")
                    val userdata = registers[6] as? Field ?: error("Reader userdata is not a stream member")
                    val output = registers[2] as? Stack ?: error("Reader size output lacks local storage")
                    val count = checkNotNull(decremented)
                    require(
                        stream.base == Input(6) && state.base == stream && userdata.base == stream && count.base == stream &&
                                output.offset >= top() && output.offset <= -8 &&
                                frame.keys.none { it < output.offset + 8 && output.offset < it + 8 })
                    val offsets = listOf(count.offset, callback.offset, state.offset, userdata.offset).sorted()
                    require(
                        offsets.zipWithNext()
                            .all { (left, right) -> right - left >= 8 }) { "Reader stream members overlap" }
                    return Proof(
                        LuaReaderLayout(stream.offset, count.offset, callback.offset, state.offset, userdata.offset),
                        instruction.offset, output.offset
                    )
                }

                else -> error("Unsupported reader prefix operation: ${instruction.operation}")
            }
        }
        error("Reader prefix exceeds instruction bound")
    }
}
