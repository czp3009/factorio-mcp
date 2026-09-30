package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** The initialized parser/reader record. Name/mode use and the returned status require separate verification. */
internal data class LuaLoadFrame(
    val reader: LuaReaderLayout, val name: Long, val mode: Long,
    val stackBase: Long, val errorHandler: Long, val nonYieldCount: Long
)

internal object SysVLuaLoadFrame {
    private sealed interface Value
    private data class Input(val register: Int) : Value
    private data class Stack(val offset: Long) : Value
    private data class Address(val address: Long) : Value
    private data class Constant(val value: Long) : Value
    private data class Member(val offset: Long) : Value
    private data class Difference(val left: Member, val right: Member) : Value
    private data class Cell(val value: Value, val index: Int)

    fun resolve(image: ElfImage, stack: LuaStackLayout, stateSize: Long): LuaLoadFrame {
        val entry = image.symbol("lua_load")
        val parser = image.symbol("_ZL8f_parserP9lua_StatePv")
        val protected = image.symbol("_Z10luaD_pcallP9lua_StatePFvS0_PvES1_ll")
        val frames = EhFrames(image)
        for (symbol in listOf(entry, parser, protected)) frames.function(symbol)
        return analyze(
            image.functionBytes(entry, 4096), entry.address, parser.address, protected.address,
            SysVLuaReader.resolve(image), stack, stateSize
        )
    }

    fun analyze(
        bytes: BinaryView, address: Long, parserAddress: Long, protectedAddress: Long,
        reader: LuaReaderLayout, stack: LuaStackLayout, stateSize: Long
    ): LuaLoadFrame {
        require(
            bytes.size in 1..4096 && address >= 0 && address <= Long.MAX_VALUE - bytes.size &&
                    parserAddress >= 0 && protectedAddress >= 0 && stateSize >= 8
        )
        val registers = MutableList<Value>(32) { Input(it) }
        registers[4] = Stack(0)
        val frame = mutableMapOf<Long, Cell>()
        val pushed = mutableSetOf<Long>()
        var testedName = false
        var counter: Long? = null
        fun top() = (registers[4] as? Stack)?.offset ?: error("Lua loader has no proven frame")
        fun frameValue(offset: Long, width: Int = 8): Value {
            require(offset >= top() && offset <= -width)
            val cells = (0 until width).map { frame[offset + it] ?: error("Lua loader has an uninitialized argument") }
            if (cells.all { it.value == Constant(0) }) return Constant(0)
            require(cells.indices.all {
                cells[it] == Cell(
                    cells.first().value,
                    it
                )
            }) { "Lua loader has a partial argument overwrite" }
            return cells.first().value
        }

        fun read(source: X64Instructions.Operand?): Value = when (source) {
            is Register -> {
                val value = registers[source.number]
                require(source.width == 8 || value is Constant && source.width in listOf(4, 16))
                value
            }

            is Immediate -> Constant(source.value)
            is Memory -> {
                require(source.width == 8 && !source.relative && source.index == null && source.base != null)
                when (val base = registers[source.base]) {
                    is Stack -> frameValue(base.offset + source.displacement)
                    Input(7) -> {
                        require(source.displacement in 0..stateSize - 8)
                        Member(source.displacement)
                    }

                    else -> error("Lua loader reads an unproven object")
                }
            }

            else -> error("Unsupported Lua loader source")
        }

        fun write(target: X64Instructions.Operand?, value: Value) {
            when (target) {
                is Register -> {
                    require(
                        target.number != 4 && (target.width == 8 || value is Constant && target.width in listOf(
                            4,
                            16
                        ))
                    )
                    registers[target.number] = value
                }

                is Memory -> {
                    require(
                        !target.relative && target.index == null &&
                                (target.width == 8 || value is Constant && target.width in listOf(1, 2, 4, 16))
                    )
                    val base = target.base?.let { registers[it] } as? Stack
                        ?: error("Lua loader writes outside its private frame")
                    val offset = base.offset + target.displacement
                    require(
                        offset >= top() && offset <= -target.width &&
                                pushed.none { it < offset + target.width && offset < it + 8 })
                    for (index in 0 until target.width) frame[offset + index] = Cell(value, index)
                }

                else -> error("Unsupported Lua loader destination")
            }
        }

        val decoder = X64Instructions(bytes)
        var position = 0L
        repeat(128) {
            require(position < bytes.size && position < 1024)
            val instruction = decoder.decode(position)
            position += instruction.size
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    val source = instruction.destination as? Register ?: error("Lua loader push is not a register")
                    val value = read(source)
                    val next = top() - 8
                    require(next >= -16384)
                    registers[4] = Stack(next)
                    for (index in 0 until 8) frame[next + index] = Cell(value, index)
                    if (source.number in listOf(3, 5, 12, 13, 14, 15) || value is Stack) pushed += next
                }

                Operation.MOV, Operation.VECTOR_MOV -> write(instruction.destination, read(instruction.source))
                Operation.VECTOR_XOR -> {
                    val target = instruction.destination as? Register ?: error("Invalid Lua loader vector")
                    require(target.number in 16..31 && target.width == 16 && instruction.source == target)
                    registers[target.number] = Constant(0)
                }

                Operation.TEST -> {
                    require(read(instruction.destination) == Input(1) && read(instruction.source) == Input(1))
                    testedName = true
                }

                Operation.CMOV -> {
                    require(testedName && instruction.condition == 5 && read(instruction.source) == Input(1))
                    // Query source names are always non-null; the fallback pointer is never selected.
                    write(instruction.destination, Input(1))
                }

                Operation.LEA -> {
                    val source = instruction.source as? Memory ?: error("Invalid Lua loader address")
                    require(source.index == null)
                    val value = if (source.relative) {
                        require(
                            source.base == null && source.displacement >= -address - position &&
                                    source.displacement <= Long.MAX_VALUE - address - position
                        )
                        Address(address + position + source.displacement)
                    } else {
                        val base =
                            source.base?.let { registers[it] } as? Stack ?: error("Lua parser record is not local")
                        val offset = base.offset + source.displacement
                        require(offset >= top() && offset <= -8)
                        Stack(offset)
                    }
                    write(instruction.destination, value)
                }

                Operation.SUB -> {
                    testedName = false
                    if (instruction.destination == Register(4, 8)) {
                        val amount = (instruction.source as? Immediate)?.value ?: error("Variable Lua loader frame")
                        require(amount in 0..16384 && amount % 8 == 0L && top() - amount >= -16384)
                        registers[4] = Stack(top() - amount)
                    } else {
                        val left =
                            read(instruction.destination) as? Member ?: error("Lua loader lacks its original stack top")
                        val right = read(instruction.source) as? Member ?: error("Lua loader lacks its stack base")
                        require(left.offset == stack.top && left.offset != right.offset)
                        write(instruction.destination, Difference(left, right))
                    }
                }

                Operation.INC -> {
                    testedName = false
                    val target = instruction.destination as? Memory ?: error("Lua loader counter is not in its state")
                    require(
                        counter == null && target.width == 2 && !target.relative && target.index == null &&
                            target.base?.let { registers[it] } == Input(7) && target.displacement in 0..stateSize - 2)
                    counter = target.displacement
                }

                Operation.CALL -> {
                    val relative =
                        (instruction.destination as? Immediate)?.value ?: error("Indirect Lua loader protection")
                    require(
                        relative >= -address && relative <= Long.MAX_VALUE - address && address + relative == protectedAddress &&
                                (8 + top()) % 16 == 0L && registers[7] == Input(7) && registers[6] == Address(
                            parserAddress
                        )
                    )
                    val parser = registers[2] as? Stack ?: error("Lua parser userdata is not local")
                    val difference =
                        registers[1] as? Difference ?: error("Lua protection lacks saved stack displacement")
                    val error = registers[8] as? Member ?: error("Lua protection lacks the original error handler")
                    val stream =
                        frameValue(parser.offset + reader.stream) as? Stack ?: error("Lua parser stream is not local")
                    require(
                        frameValue(stream.offset + reader.count) == Constant(0) &&
                                frameValue(stream.offset + reader.callback) == Input(6) &&
                                frameValue(stream.offset + reader.state) == Input(7) &&
                                frameValue(stream.offset + reader.userdata) == Input(2)
                    ) { "Lua reader initial arguments disagree with parser dispatch" }
                    fun argument(register: Int): Long = frame.keys.filter { offset ->
                        offset >= parser.offset && offset <= -8 && frame[offset] == Cell(Input(register), 0) &&
                                (0 until 8).all { frame[offset + it] == Cell(Input(register), it) }
                    }.single() - parser.offset

                    val name = argument(1)
                    val mode = argument(8)
                    val fields = listOf(reader.stream, name, mode).sorted()
                    require(fields.first() >= 0 && fields.zipWithNext().all { (left, right) -> right - left >= 8 })
                    val count = checkNotNull(counter)
                    val stateFields = listOf(
                        stack.top to 8,
                        difference.right.offset to 8,
                        error.offset to 8,
                        count to 2
                    ).sortedBy { it.first }
                    require(stateFields.zipWithNext().all { (left, right) -> left.first + left.second <= right.first })
                    return LuaLoadFrame(reader, name, mode, difference.right.offset, error.offset, count)
                }

                else -> error("Unsupported Lua loader prefix operation: ${instruction.operation}")
            }
        }
        error("Lua loader prefix exceeds instruction bound")
    }
}
