package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Callback argument forwarding and the normal save/restore path, not an interpretation of exception handlers. */
internal data class LuaProtection(val counter: Long, val handler: Long, val status: Long, val recordSize: Long)

internal object SysVLuaProtection {
    private sealed interface Value
    private data class Input(val register: Int) : Value
    private data class Stack(val offset: Long) : Value
    private data class Member(val offset: Long, val width: Int) : Value
    private data class Constant(val bits: Long) : Value
    private data class Reload(val offset: Long, val width: Int) : Value
    private data object Unknown : Value

    fun resolve(image: ElfImage, stateSize: Long): LuaProtection {
        val function = image.symbol("_Z20luaD_rawrunprotectedP9lua_StatePFvS0_PvES1_")
        EhFrames(image).function(function)
        return analyze(image.functionBytes(function, 4096), stateSize)
    }

    fun analyze(bytes: BinaryView, stateSize: Long): LuaProtection {
        require(bytes.size in 1..4096 && stateSize in 8..(64 * 1024 * 1024))
        val registers = MutableList<Value>(16) { Input(it) }
        registers[4] = Stack(0)
        val frame = mutableMapOf<Pair<Long, Int>, Value>()
        val pushed = mutableSetOf<Long>()
        val reads = mutableSetOf<Member>()
        val restored = mutableSetOf<Member>()
        var handler: Pair<Member, Stack>? = null
        var called = false
        var originalFrame: Map<Pair<Long, Int>, Value> = emptyMap()
        var frameBottom = 0L
        fun top() = (registers[4] as? Stack)?.offset ?: error("Unknown Lua protection frame")
        fun register(source: Register): Value {
            val value = registers[source.number]
            return when (value) {
                is Member -> {
                    require(source.width >= value.width)
                    value
                }

                is Reload -> {
                    require(source.width == value.width || source.width == 8 && value.width == 4)
                    value
                }

                is Constant -> if (source.width == 8) value else {
                    require(source.width in listOf(1, 2, 4))
                    Constant(value.bits and ((1L shl (source.width * 8)) - 1))
                }

                else -> {
                    require(source.width == 8) { "Lua protection truncates pointer provenance" }
                    value
                }
            }
        }

        fun location(memory: Memory): Pair<Boolean, Long> {
            require(!memory.relative && memory.index == null && memory.base != null)
            return when (val base = registers[memory.base]) {
                is Stack -> {
                    val offset = base.offset + memory.displacement
                    require(offset >= top() && offset <= -memory.width) { "Lua protection accesses outside its frame" }
                    true to offset
                }

                Input(7) -> {
                    require(memory.displacement in 0..stateSize - memory.width) { "Lua protection exceeds state bounds" }
                    false to memory.displacement
                }

                else -> error("Unproven Lua protection memory base")
            }
        }

        fun read(source: X64Instructions.Operand?): Value = when (source) {
            is Register -> register(source)
            is Immediate -> Constant(source.value)
            is Memory -> {
                val (local, offset) = location(source)
                if (local) {
                    if (called && offset !in pushed) Reload(offset, source.width)
                    else frame[offset to source.width] ?: error("Unproven Lua protection frame value")
                } else {
                    require(!called && source.width in listOf(2, 8))
                    Member(offset, source.width).also { reads += it }
                }
            }

            else -> error("Unsupported Lua protection operand")
        }

        fun write(target: X64Instructions.Operand?, value: Value) {
            when (target) {
                is Register -> {
                    require(
                        target.width == 8 || value is Member && target.width >= value.width ||
                                value is Constant || value is Reload && target.width == value.width
                    )
                    if (target.number == 4) require(target.width == 8 && value is Stack)
                    registers[target.number] = if (value is Constant && target.width == 4)
                        Constant(value.bits and 0xffffffffL) else value
                }

                is Memory -> {
                    val (local, offset) = location(target)
                    if (local) {
                        require(!called && pushed.none { it < offset + target.width && offset < it + 8 }) {
                            "Lua protection overwrites a saved register or post-call frame"
                        }
                        require(target.width == 8 || value is Constant || value is Member && value.width == target.width)
                        require(frame.keys.none { (start, width) -> start < offset + target.width && offset < start + width })
                        frame[offset to target.width] = value
                    } else if (!called) {
                        require(
                            handler == null && target.width == 8 && value is Stack && value.offset >= top() &&
                                    value.offset <= -8 && frame[value.offset to 8] == Member(offset, 8)
                        ) {
                            "Lua protection does not link a saved handler record"
                        }
                        handler = Member(offset, 8) to value
                    } else {
                        val member =
                            if (value is Reload) originalFrame[value.offset to value.width] as? Member else value as? Member
                        require(
                            member != null && member == Member(
                                offset,
                                target.width
                            ) && member in reads && restored.add(member)
                        ) {
                            "Lua protection restores a different state member"
                        }
                    }
                }

                else -> error("Unsupported Lua protection destination")
            }
        }

        val decoder = X64Instructions(bytes)
        val visited = mutableSetOf<Long>()
        var position = 0L
        repeat(128) {
            require(position in 0 until bytes.size && visited.add(position)) { "Lua protection normal path loops or leaves function" }
            val instruction = decoder.decode(position)
            position += instruction.size
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    require(!called)
                    val source = instruction.destination as? Register ?: error("Unsupported Lua protection push")
                    require(source.width == 8)
                    val value = register(source)
                    val next = top() - 8
                    require(next >= -16384)
                    registers[4] = Stack(next)
                    frame[next to 8] = value
                    pushed += next
                }

                Operation.POP -> {
                    require(called)
                    val target = instruction.destination as? Register ?: error("Unsupported Lua protection pop")
                    require(target.width == 8 && target.number != 4 && top() in pushed)
                    val offset = top()
                    registers[target.number] = checkNotNull(frame[offset to 8])
                    registers[4] = Stack(offset + 8)
                }

                Operation.ADD, Operation.SUB -> {
                    require(instruction.destination == Register(4, 8))
                    val amount = (instruction.source as? Immediate)?.value ?: error("Dynamic Lua protection frame")
                    require(amount in 0..16384 && amount % 8 == 0L)
                    require((instruction.operation == Operation.ADD) == called)
                    val next = top() + if (called) amount else -amount
                    require(next in -16384..0)
                    registers[4] = Stack(next)
                }

                Operation.MOV, Operation.MOVZX -> write(instruction.destination, read(instruction.source))
                Operation.LEA -> {
                    require(!called)
                    val target =
                        instruction.destination as? Register ?: error("Unsupported Lua protection record address")
                    val source = instruction.source as? Memory ?: error("Unsupported Lua protection record address")
                    require(target.width == 8 && target.number != 4 && !source.relative && source.index == null)
                    val base =
                        source.base?.let { registers[it] } as? Stack ?: error("Record is not in the private frame")
                    val offset = base.offset + source.displacement
                    require(offset >= top() && offset <= -8)
                    write(target, Stack(offset))
                }

                Operation.CALL -> {
                    require(
                        !called && read(instruction.destination) == Input(6) && registers[7] == Input(7) &&
                                registers[6] == Input(2) && (8 + top()) % 16 == 0L
                    ) { "Lua callback argument ABI differs" }
                    // The callback has exactly two arguments, verified above. Scratch registers may still
                    // contain the linked record address; they are not additional callback parameters.
                    require(handler != null && reads.size == 2 && reads.count { it.width == 2 } == 1 && reads.count { it.width == 8 } == 1)
                    originalFrame = frame.toMap()
                    frameBottom = top()
                    for (index in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) registers[index] = Unknown
                    called = true
                }

                Operation.RET -> {
                    require(
                        called && top() == 0L && restored == reads &&
                                listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Input(it) }) {
                        "Lua protection fails to restore state or the System V frame"
                    }
                    val result = registers[0] as? Reload ?: error("Lua protection does not return its saved status")
                    require(result.width == 4 && originalFrame[result.offset to 4] == Constant(0))
                    val (member, record) = checkNotNull(handler)
                    val counter = reads.single { it.width == 2 }
                    val relative = result.offset - record.offset
                    require(
                        record.offset >= frameBottom && relative in 8..256 && result.offset <= -4 &&
                                (counter.offset + 2 <= member.offset || member.offset + 8 <= counter.offset)
                    )
                    return LuaProtection(counter.offset, member.offset, relative, relative + 4)
                }

                else -> error("Unsupported Lua protection normal-path operation: ${instruction.operation}")
            }
        }
        error("Lua protection normal path exceeds instruction bound")
    }
}
