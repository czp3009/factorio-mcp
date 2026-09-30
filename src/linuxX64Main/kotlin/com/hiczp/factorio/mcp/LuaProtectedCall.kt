package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Specialized for two/ten arguments, one result, no error handler and no continuation.
 * Callers must validate the live frame extent and all three native guards immediately before dispatch.
 */
internal data class LuaProtectedCall(val arguments: Int, val status: Long, val frameTop: Long)

internal object SysVLuaProtectedCall {
    private sealed interface Value
    private data object State : Value
    private data object Continuation : Value
    private data object Result : Value
    private data class Original(val register: Int) : Value
    private data class Constant(val bits: Long) : Value
    private data class Frame(val offset: Long) : Value
    private data class Address(val address: Long) : Value
    private data class Load(val owner: Value, val offset: Long, val width: Int) : Value
    private data class Difference(val left: Value, val right: Value) : Value
    private data class Offset(val base: Value, val amount: Long) : Value
    private data class Quotient(val value: Value, val shift: Int) : Value
    private data class Cell(val value: Value, val index: Int)
    private data class Comparison(val left: Value, val right: Value, val width: Int)

    fun resolve(image: ElfImage, stack: LuaStackLayout, loader: LuaLoadFrame, stateSize: Long): LuaProtectedCall {
        val entry = image.symbol("lua_pcallk")
        val callback = image.symbol("_ZL6f_callP9lua_StatePv")
        val protected = image.symbol("_Z10luaD_pcallP9lua_StatePFvS0_PvES1_ll")
        val abort = image.symbol("lua_traceandabort")
        val frames = EhFrames(image)
        for (symbol in listOf(entry, callback, protected, abort)) frames.function(symbol)
        return analyze(
            image.functionBytes(entry, 4096), entry.address, callback.address, protected.address,
            abort.address, stack, loader.stackBase, SysVLuaCallRecord.resolve(image), stateSize
        )
    }

    fun analyze(
        bytes: BinaryView, address: Long, callback: Long, protected: Long, abort: Long,
        stack: LuaStackLayout, stackBase: Long, record: LuaCallRecord, stateSize: Long
    ): LuaProtectedCall {
        val proofs = listOf(5, 6).mapNotNull { arguments ->
            try {
                val short = specialize(
                    bytes,
                    address,
                    callback,
                    protected,
                    abort,
                    stack,
                    stackBase,
                    record,
                    stateSize,
                    arguments,
                    2
                )
                val viewport = specialize(
                    bytes,
                    address,
                    callback,
                    protected,
                    abort,
                    stack,
                    stackBase,
                    record,
                    stateSize,
                    arguments,
                    10
                )
                require(short == viewport)
                short
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        }
        return proofs.singleOrNull() ?: error("Lua protected-call ABI is unsupported or ambiguous")
    }

    internal fun specialize(
        bytes: BinaryView, address: Long, callback: Long, protected: Long, abort: Long,
        stack: LuaStackLayout, stackBase: Long, record: LuaCallRecord, stateSize: Long,
        arguments: Int, nargs: Int
    ): LuaProtectedCall {
        require(
            bytes.size in 1..4096 && address >= 0 && address <= Long.MAX_VALUE - bytes.size &&
                    callback >= 0 && protected >= 0 && abort >= 0 && arguments in 5..6 && nargs in listOf(2, 10)
        )
        require(
            stateSize >= 8 && stackBase in 0..stateSize - 8 && stack.top in 0..stateSize - 8 &&
                    stack.callInfo in 0..stateSize - 8
        )
        val instructions = X64Instructions(bytes).all(1024).associateBy { it.offset }
        val registers = MutableList<Value>(16) { Original(it) }
        registers[7] = State
        registers[6] = Constant(nargs.toLong())
        registers[2] = Constant(1)
        registers[1] = Constant(0)
        registers[8] = if (arguments == 5) Continuation else Constant(0)
        if (arguments == 6) registers[9] = Continuation
        registers[4] = Frame(0)
        val frame = mutableMapOf<Long, Cell>()
        val saved = mutableSetOf<Long>()
        val top = Load(State, stack.top, 8)
        val callInfo = Load(State, stack.callInfo, 8)
        val function = Offset(top, -(nargs + 1) * stack.valueSize)
        val shift = stack.valueSize.countTrailingZeroBits()
        var comparison: Comparison? = null
        var continuationTested = false
        var countChecked = false
        var status: Long? = null
        var frameTop: Long? = null
        var called = false
        val frameReads = mutableSetOf<Long>()
        fun frameTop() = (registers[4] as? Frame)?.offset ?: error("Lua protected call lost its frame")
        fun bits(value: Long, width: Int) = if (width == 8) value else value and ((1L shl (width * 8)) - 1)
        fun signed(value: Long, width: Int) = value shl (64 - width * 8) shr (64 - width * 8)
        fun frameRead(offset: Long, width: Int): Value {
            require(offset >= frameTop() && offset <= -width)
            val first = frame[offset] ?: error("Uninitialized Lua call record")
            require((0 until width).all { frame[offset + it] == Cell(first.value, it) })
            return first.value
        }

        fun read(operand: X64Instructions.Operand?): Value = when (operand) {
            is Immediate -> Constant(operand.value)
            is Register -> {
                val value = registers[operand.number]
                require(operand.width == 8 || operand.width == 4 && (value is Constant || value == Result)) {
                    "Lua protected call truncates a pointer"
                }
                if (value is Constant) Constant(bits(value.bits, operand.width)) else value
            }

            is Memory -> {
                require(!operand.relative && operand.index == null && operand.width in listOf(1, 8))
                when (val owner = operand.base?.let { registers[it] }) {
                    is Frame -> frameRead(owner.offset + operand.displacement, operand.width)
                    State -> {
                        require(!called && operand.displacement in 0..stateSize - operand.width)
                        Load(owner, operand.displacement, operand.width)
                    }

                    callInfo -> {
                        require(!called && operand.width == 8 && operand.displacement in 0..4096)
                        frameReads += operand.displacement
                        Load(callInfo, operand.displacement, 8)
                    }

                    else -> error("Lua protected call reads an unproven object")
                }
            }

            else -> error("Unsupported Lua protected-call operand")
        }

        fun write(operand: X64Instructions.Operand?, value: Value) {
            when (operand) {
                is Register -> {
                    require(
                        operand.number != 4 && (operand.width == 8 || operand.width == 4 &&
                                (value is Constant || value == Result))
                    )
                    registers[operand.number] =
                        if (value is Constant) Constant(bits(value.bits, operand.width)) else value
                }

                is Memory -> {
                    require(
                        !called && !operand.relative && operand.index == null && operand.width in listOf(4, 8) &&
                                (operand.width == 8 || value is Constant)
                    )
                    val base =
                        operand.base?.let { registers[it] } as? Frame ?: error("Lua protected call writes game memory")
                    val offset = base.offset + operand.displacement
                    require(
                        offset >= frameTop() && offset <= -operand.width &&
                                saved.none { it < offset + operand.width && offset < it + 8 })
                    for (index in 0 until operand.width) frame[offset + index] = Cell(value, index)
                }

                else -> error("Unsupported Lua protected-call write")
            }
        }

        fun direct(instruction: X64Instructions.Instruction): Long {
            val relative = (instruction.destination as? Immediate)?.value ?: error("Indirect Lua protected call")
            require(relative >= -address && relative <= Long.MAX_VALUE - address)
            return address + relative
        }

        fun rejects(start: Long) {
            var cursor = start
            repeat(8) {
                val instruction = instructions[cursor] ?: error("Lua guard leaves function bounds")
                when (instruction.operation) {
                    Operation.LEA -> require(
                        instruction.destination is Register && instruction.source is Memory &&
                                instruction.source.relative && instruction.source.index == null
                    )

                    Operation.CALL -> {
                        require(direct(instruction) == abort) { "Lua guard does not reject the invalid state" }
                        return
                    }

                    Operation.NOP, Operation.ENDBR -> Unit
                    else -> error("Unsupported Lua guard failure path")
                }
                cursor += instruction.size
            }
            error("Lua guard failure path exceeds bound")
        }

        val visited = mutableSetOf<Long>()
        var position = 0L
        repeat(256) {
            require(visited.add(position)) { "Lua protected-call specialization contains a loop" }
            val instruction = instructions[position] ?: error("Lua protected call leaves function bounds")
            position += instruction.size
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    require(!called && instruction.destination is Register && instruction.destination.width == 8)
                    val value = read(instruction.destination)
                    val next = frameTop() - 8
                    require(next >= -16384)
                    registers[4] = Frame(next)
                    for (index in 0 until 8) frame[next + index] = Cell(value, index)
                    saved += next
                }

                Operation.POP -> {
                    val value = frameRead(frameTop(), 8)
                    val offset = frameTop()
                    write(instruction.destination, value)
                    require(saved.remove(offset))
                    registers[4] = Frame(offset + 8)
                }

                Operation.MOV -> write(instruction.destination, read(instruction.source))
                Operation.MOVSX -> {
                    val source = instruction.source as? Register ?: error("Unsupported Lua argument extension")
                    val value = read(source) as? Constant ?: error("Lua argument is not a known integer")
                    require(source.width == 4 && (instruction.destination as? Register)?.width == 8)
                    write(instruction.destination, Constant(signed(value.bits, 4)))
                }

                Operation.LEA -> {
                    val source = instruction.source as? Memory ?: error("Unsupported Lua call address")
                    require(source.index == null)
                    val value = if (source.relative) {
                        require(source.displacement >= -address - position && source.displacement <= Long.MAX_VALUE - address - position)
                        Address(address + position + source.displacement)
                    } else when (val base = source.base?.let { registers[it] }) {
                        is Frame -> Frame(base.offset + source.displacement)
                        is Constant -> Constant(base.bits + source.displacement)
                        else -> error("Unsupported Lua call address base")
                    }
                    write(instruction.destination, value)
                }

                Operation.ADD, Operation.SUB -> {
                    comparison = null
                    if (instruction.destination == Register(4, 8)) {
                        val amount = (instruction.source as? Immediate)?.value ?: error("Variable Lua call frame")
                        require(amount in 0..16384 && amount % 8 == 0L)
                        val next = frameTop() + if (instruction.operation == Operation.ADD) amount else -amount
                        require(next in -16384..0 && (instruction.operation == Operation.SUB || saved.none { it < next }))
                        registers[4] = Frame(next)
                    } else {
                        val left = read(instruction.destination)
                        val right = read(instruction.source)
                        val value = if (left is Constant && right is Constant) {
                            Constant(if (instruction.operation == Operation.ADD) left.bits + right.bits else left.bits - right.bits)
                        } else {
                            require(instruction.operation == Operation.SUB && (instruction.destination as? Register)?.width == 8)
                            if (right is Constant) Offset(left, -right.bits) else Difference(left, right)
                        }
                        write(instruction.destination, value)
                    }
                }

                Operation.SAR, Operation.SHL -> {
                    comparison = null
                    val amount = (instruction.source as? Immediate)?.value ?: error("Variable Lua value stride")
                    require(amount in 0..7 && (instruction.destination as? Register)?.width == 8)
                    val value = read(instruction.destination)
                    write(
                        instruction.destination, if (value is Constant) {
                            Constant(if (instruction.operation == Operation.SAR) value.bits shr amount.toInt() else value.bits shl amount.toInt())
                        } else {
                            require(instruction.operation == Operation.SAR)
                            Quotient(value, amount.toInt())
                        }
                    )
                }

                Operation.XOR -> {
                    require(instruction.destination == instruction.source && instruction.destination is Register)
                    write(instruction.destination, Constant(0))
                    comparison = null
                }

                Operation.CMP, Operation.TEST -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    val width = when (val operand = instruction.destination) {
                        is Register -> operand.width
                        is Memory -> operand.width
                        else -> error("Unsupported Lua comparison")
                    }
                    if (instruction.operation == Operation.TEST) {
                        require(left == right)
                        if (left == Continuation) continuationTested = true
                        comparison = Comparison(if (left == Continuation) Constant(0) else left, Constant(0), width)
                    } else comparison = Comparison(left, right, width)
                }

                Operation.JCC -> {
                    val test = checkNotNull(comparison) { "Lua branch has unproven flags" }
                    val target = (instruction.destination as? Immediate)?.value ?: error("Invalid Lua branch")
                    val condition = instruction.condition
                    val taken = if (test.left is Constant && test.right is Constant) {
                        val left = bits(test.left.bits, test.width)
                        val right = bits(test.right.bits, test.width)
                        when (condition) {
                            4 -> left == right
                            5 -> left != right
                            12 -> signed(left, test.width) < signed(right, test.width)
                            14 -> signed(left, test.width) <= signed(right, test.width)
                            else -> error("Unsupported Lua constant branch")
                        }
                    } else {
                        require(!called)
                        rejects(target)
                        when {
                            condition == 14 && test.width == 8 && test.left == Quotient(
                                Difference(top, Load(callInfo, stack.function, 8)), shift
                            ) && test.right == Constant((nargs + 1).toLong()) -> {
                                require(!countChecked)
                                countChecked = true
                            }

                            condition == 5 && test.width == 1 && test.right == Constant(0) && test.left is Load &&
                                    test.left.owner == State && test.left.width == 1 -> {
                                require(status == null)
                                status = test.left.offset
                            }

                            condition == 12 && test.width == 8 && test.right == Constant(1L - nargs) && test.left is Quotient -> {
                                val quotient = test.left
                                val difference =
                                    quotient.value as? Difference ?: error("Missing Lua result capacity subtraction")
                                val limit = difference.left as? Load ?: error("Missing Lua frame limit")
                                require(
                                    frameTop == null && quotient.shift == shift && difference.right == top &&
                                            limit.owner == callInfo && limit.width == 8 && limit.offset != stack.function
                                )
                                frameTop = limit.offset
                            }

                            else -> error("Unsupported Lua protected-call precondition: $test / $condition")
                        }
                        false // Admission must establish the recorded native guard before using this specialized path.
                    }
                    if (taken) position = target
                }

                Operation.JMP -> position =
                    (instruction.destination as? Immediate)?.value ?: error("Indirect Lua branch")

                Operation.CALL -> {
                    require(
                        !called && direct(instruction) == protected && (8 + frameTop()) % 16 == 0L &&
                                registers[7] == State && registers[6] == Address(callback) && registers[8] == Constant(0) &&
                                registers[1] == Difference(function, Load(State, stackBase, 8))
                    ) {
                        "Lua protected-call arguments do not match the query ABI"
                    }
                    val userdata = registers[2] as? Frame ?: error("Lua call record is not private")
                    require(
                        frameRead(userdata.offset + record.function, 8) == function &&
                                frameRead(userdata.offset + record.results, 4) == Constant(1)
                    )
                    require(continuationTested && countChecked && status != null && frameTop != null)
                    called = true
                    for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) registers[register] = Original(register)
                    registers[0] = Result
                    comparison = null
                }

                Operation.RET -> {
                    require(
                        called && registers[0] == Result && frameTop() == 0L && saved.isEmpty() &&
                                listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Original(it) })
                    val statusOffset = checkNotNull(status)
                    require(listOf(stack.top, stack.callInfo, stackBase).none { statusOffset in it until it + 8 })
                    val limit = checkNotNull(frameTop)
                    require(limit + 8 <= stack.function || stack.function + 8 <= limit)
                    require(frameReads.all { it == stack.function || it == limit })
                    return LuaProtectedCall(arguments, statusOffset, limit)
                }

                else -> error("Unsupported Lua protected-call operation: ${instruction.operation}")
            }
        }
        error("Lua protected-call specialization exceeds instruction bound")
    }
}
