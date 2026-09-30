package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Initial allocation and self-reference evidence, not proof that a live state is ready to execute. */
internal data class LuaAllocation(
    val size: Long,
    val global: Long,
    val globalOffset: Long,
    val allocator: Long,
    val userdata: Long,
    val mainState: Long,
)

/** Analyzes only the non-null initialization prefix before the first subsequent call. */
internal object SysVLuaAllocation {
    private sealed interface Value
    private data class Input(val register: Int) : Value
    private data class Stack(val offset: Long) : Value
    private data class Allocation(val offset: Long = 0) : Value
    private data class Constant(val bits: Long) : Value
    private data object Unknown : Value

    fun resolve(image: ElfImage): LuaAllocation {
        val symbol = image.symbol("lua_newstate")
        EhFrames(image).function(symbol)
        return analyze(image.functionBytes(symbol, 4096))
    }

    fun analyze(bytes: BinaryView): LuaAllocation {
        require(bytes.size in 1..4096)
        val decoder = X64Instructions(bytes)
        val registers = MutableList<Value>(32) { Input(it) }
        registers[4] = Stack(0)
        val saved = mutableMapOf<Pair<Long, Int>, Value>()
        val fields = mutableMapOf<Pair<Long, Int>, Value>()
        val volatile = setOf(0, 1, 2, 6, 7, 8, 9, 10, 11)
        var size: Long? = null
        var checked = false
        var nullTest = false
        var position = 0L
        fun top() = (registers[4] as? Stack)?.offset ?: error("Unknown Lua allocation frame")
        fun read(register: Register): Value {
            val value = registers[register.number]
            return when {
                register.width == 8 -> value
                register.width == 16 && value == Constant(0) -> value
                value is Constant && register.width in listOf(1, 2, 4) ->
                    Constant(value.bits and ((1L shl (register.width * 8)) - 1))

                else -> error("Lua allocation prefix truncates pointer provenance")
            }
        }

        fun write(register: Register, value: Value) {
            require(register.width == 8 || value is Constant && register.width in listOf(4, 16)) {
                "Unsupported Lua allocation register write"
            }
            if (register.number == 4) require(value is Stack && value.offset in -16384..0)
            registers[register.number] = if (register.width == 4 && value is Constant)
                Constant(value.bits and 0xffffffffL) else value
        }

        fun address(memory: Memory): Pair<Boolean, Long> {
            require(!memory.relative && memory.index == null && memory.base != null)
            return when (val base = registers[memory.base]) {
                is Stack -> {
                    val offset = base.offset + memory.displacement
                    require(offset >= top() && offset <= -memory.width) { "Lua allocation accesses outside its frame" }
                    true to offset
                }

                is Allocation -> {
                    require(checked)
                    val offset = base.offset + memory.displacement
                    require(offset in 0..checkNotNull(size) - memory.width) { "Lua initialization exceeds allocation" }
                    false to offset
                }

                else -> error("Unknown Lua initialization memory base")
            }
        }

        fun operand(value: X64Instructions.Operand?): Value = when (value) {
            is Register -> read(value)
            is Immediate -> Constant(value.value)
            is Memory -> {
                val (frame, offset) = address(value)
                require(frame) { "Lua allocation prefix reads uninitialized heap memory" }
                saved[offset to value.width] ?: error("Unknown Lua allocation saved value")
            }

            else -> error("Unsupported Lua allocation operand")
        }

        fun finish(): LuaAllocation {
            require(checked)
            fun field(value: Value): Long = fields.entries.single { it.key.second == 8 && it.value == value }.key.first
            val allocate = field(Input(7))
            val userdata = field(Input(6))
            val main = field(Allocation())
            val global = fields.entries.single { (_, value) -> value is Allocation && value.offset != 0L }
            val globalOffset = (global.value as Allocation).offset
            val globalMember = global.key.first
            val total = checkNotNull(size)
            require(
                global.key.second == 8 && globalOffset % 8 == 0L && globalMember % 8 == 0L &&
                        globalMember in 0..globalOffset - 8 &&
                        listOf(allocate, userdata, main).all { it % 8 == 0L && it in globalOffset..total - 8 }) {
                "Lua allocation does not separate state and embedded global members"
            }
            return LuaAllocation(
                total, globalMember, globalOffset, allocate - globalOffset,
                userdata - globalOffset, main - globalOffset
            )
        }
        repeat(128) {
            require(position < bytes.size && position < 512) { "Lua allocation prefix exceeds bound" }
            val instruction = decoder.decode(position)
            position += instruction.size
            val tested = nullTest
            nullTest = false
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    val source = instruction.destination as? Register ?: error("Unsupported Lua allocation push")
                    require(source.width == 8)
                    val value = read(source)
                    val next = top() - 8
                    require(next >= -16384)
                    registers[4] = Stack(next)
                    saved[next to 8] = value
                }

                Operation.MOV, Operation.VECTOR_MOV -> {
                    val value = operand(instruction.source)
                    when (val target = instruction.destination) {
                        is Register -> write(target, value)
                        is Memory -> {
                            val (frame, offset) = address(target)
                            require(target.width == 8 || value is Constant) { "Lua initialization truncates a pointer" }
                            val destination = if (frame) saved else fields
                            require(destination.keys.none { (start, width) ->
                                start < offset + target.width && offset < start + width
                            }) { "Overlapping Lua allocation initialization" }
                            require(frame || value !is Stack) { "Lua allocation exposes its frame" }
                            destination[offset to target.width] = value
                        }

                        else -> error("Unsupported Lua allocation destination")
                    }
                }

                Operation.ADD, Operation.SUB -> {
                    val target = instruction.destination as? Register ?: error("Lua allocation modifies memory")
                    val amount = (instruction.source as? Immediate)?.value ?: error("Dynamic Lua allocation adjustment")
                    require(target.width == 8 && amount in 0..16384)
                    val delta = if (instruction.operation == Operation.ADD) amount else -amount
                    when (val value = read(target)) {
                        is Stack -> {
                            require(target.number == 4 && delta <= 0)
                            write(target, Stack(value.offset + delta))
                        }

                        is Allocation -> {
                            require(checked && value.offset + delta in 0 until checkNotNull(size))
                            write(target, Allocation(value.offset + delta))
                        }

                        else -> error("Lua allocation adjusts unknown pointer")
                    }
                }

                Operation.LEA -> {
                    val target = instruction.destination as? Register ?: error("Unsupported Lua allocation address")
                    val source = instruction.source as? Memory ?: error("Unsupported Lua allocation address")
                    require(checked && target.width == 8 && !source.relative && source.index == null)
                    val base = source.base?.let { registers[it] } as? Allocation ?: error("Unknown allocation base")
                    val offset = base.offset + source.displacement
                    require(offset in 0 until checkNotNull(size))
                    write(target, Allocation(offset))
                }

                Operation.XOR, Operation.VECTOR_XOR -> {
                    val target = instruction.destination as? Register ?: error("Unsupported Lua allocation zero")
                    require(target == instruction.source && target.width in listOf(4, 8, 16))
                    write(target, Constant(0))
                }

                Operation.TEST -> {
                    require(
                        size != null && !checked && instruction.destination == instruction.source &&
                                operand(instruction.destination) == Allocation()
                    ) { "Unexpected Lua allocation test" }
                    nullTest = true
                }

                Operation.JCC -> {
                    val target = (instruction.destination as? Immediate)?.value ?: error("Indirect allocation branch")
                    require(tested && instruction.condition == 4 && target in position until bytes.size)
                    checked = true // Continue only the allocator's non-null fallthrough.
                }

                Operation.CALL -> {
                    require((8 + top()) % 16 == 0L && volatile.none { registers[it] is Stack }) {
                        "Lua allocation call exposes or misaligns its frame"
                    }
                    if (size != null) {
                        require(instruction.destination is Immediate) { "Unexpected second allocation callback" }
                        return finish()
                    }
                    require(
                        operand(instruction.destination) == Input(7) && registers[7] == Input(6) &&
                                registers[6] == Constant(0) && registers[2] is Constant
                    ) {
                        "Lua allocation does not use its allocator and userdata arguments"
                    }
                    size = (registers[1] as? Constant)?.bits ?: error("Nonconstant Lua allocation size")
                    require(checkNotNull(size) in 16..(64 * 1024 * 1024))
                    for (register in volatile) registers[register] = Unknown
                    registers[0] = Allocation()
                }

                else -> error("Unsupported Lua allocation prefix operation: ${instruction.operation}")
            }
        }
        error("Lua allocation prefix has no complete initialization evidence")
    }
}
