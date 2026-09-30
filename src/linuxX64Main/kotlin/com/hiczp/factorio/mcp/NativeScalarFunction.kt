package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Evaluates a selected pure scalar path. Only independently verified switch tables may be read. */
internal object NativeScalarFunction {
    private sealed interface Value
    private data class Number(val value: Long) : Value
    private data class Saved(val register: Int) : Value
    private data class Frame(val offset: Long) : Value

    fun evaluate(image: ElfImage, function: ElfImage.Symbol, input: Int): Int {
        val tables = X64JumpTables.resolve(image, function)
        return evaluate(
            X64ControlFlow(X64Instructions(image.functionBytes(function, 4096)).all(1024), tables),
            function.address, tables, input
        )
    }

    fun evaluate(flow: X64ControlFlow, address: Long, tables: List<X64JumpTables.Table>, input: Int): Int {
        require(address >= 0 && address <= Long.MAX_VALUE - flow.instructions.last().offset - 15)
        val registers = (0..15).associateWith<Int, Value> { Saved(it) }.toMutableMap()
        registers[7] = Number(input.toLong() and 0xffffffffL)
        registers[4] = Frame(0)
        val stack = mutableMapOf<Long, Value>()
        val path = mutableSetOf<Long>()
        var comparison: Pair<Number, Number>? = null
        var compareWidth = 0
        fun mask(value: Long, width: Int): Long {
            require(width in listOf(4, 8))
            return if (width == 8) value else value and 0xffffffffL
        }

        fun read(operand: Operand?): Value = when (operand) {
            is Immediate -> Number(operand.value)
            is Register -> checkNotNull(registers[operand.number]).let {
                if (it is Number) Number(mask(it.value, operand.width))
                else it.also { require(operand.width == 8) { "Conversion reads an unknown scalar argument" } }
            }

            else -> error("Conversion reads unverified memory")
        }

        fun number(operand: Operand?): Long = (read(operand) as? Number)?.value
            ?: error("Conversion uses a non-scalar operand")

        fun write(operand: Operand?, value: Value) {
            val target = operand as? Register ?: error("Conversion writes external memory")
            require(target.number in 0..15)
            registers[target.number] = if (value is Number) Number(mask(value.value, target.width))
            else value.also { require(target.width == 8) }
        }

        fun top() = (registers[4] as? Frame)?.offset ?: error("Conversion lost its frame")
        var site = 0L
        while (true) {
            require(path.size < 256 && path.add(site)) { "Conversion path loops or exceeds its bound" }
            val instruction = flow.body.getValue(site)
            var next = site + instruction.size
            when (instruction.operation) {
                Operation.PUSH -> {
                    require(instruction.destination is Register && instruction.destination.width == 8)
                    val value = read(instruction.destination)
                    val offset = top() - 8
                    require(offset in -256..-8)
                    stack[offset] = value
                    registers[4] = Frame(offset)
                }

                Operation.POP -> {
                    val target = instruction.destination as? Register ?: error("Non-register conversion pop")
                    require(target.width == 8 && target.number != 4)
                    val offset = top()
                    write(target, checkNotNull(stack.remove(offset)))
                    registers[4] = Frame(offset + 8)
                }

                Operation.MOV -> write(instruction.destination, read(instruction.source))
                Operation.LEA -> {
                    val source = instruction.source as? Memory ?: error("Conversion address is not decoded")
                    require(!source.relative && source.base != null && source.index == null)
                    write(instruction.destination, Number(number(Register(source.base, 8)) + source.displacement))
                }

                Operation.ADD, Operation.SUB, Operation.XOR -> {
                    if (instruction.destination == Register(4, 8) && instruction.operation != Operation.XOR) {
                        val amount = (instruction.source as? Immediate)?.value ?: error("Dynamic scalar frame")
                        require(amount in 8..256 && amount % 8 == 0L)
                        val previous = top()
                        val adjusted = previous + if (instruction.operation == Operation.ADD) amount else -amount
                        require(adjusted in -256..0 && stack.keys.none { it >= previous && it < adjusted }) {
                            "Scalar frame adjustment discards saved registers or exceeds bounds"
                        }
                        registers[4] = Frame(adjusted)
                    } else {
                        val left =
                            if (instruction.operation == Operation.XOR && instruction.destination == instruction.source)
                                0L else number(instruction.destination)
                        val right =
                            if (instruction.operation == Operation.XOR && instruction.destination == instruction.source)
                                0L else number(instruction.source)
                        write(
                            instruction.destination, Number(
                                when (instruction.operation) {
                                    Operation.ADD -> left + right
                                    Operation.SUB -> left - right
                                    else -> left xor right
                                }
                            )
                        )
                    }
                    comparison = null
                }

                Operation.CMP -> {
                    val target = instruction.destination as? Register ?: error("Conversion comparison reads memory")
                    compareWidth = target.width
                    require(compareWidth == 4)
                    comparison = Number(number(target)) to Number(number(instruction.source))
                }

                Operation.JCC -> {
                    val (left, right) = checkNotNull(comparison) { "Conversion branch lacks comparison flags" }
                    val selected = ScalarExpression.evaluate(
                        ScalarExpression.Select(
                            checkNotNull(instruction.condition),
                            ScalarExpression.Literal(left.value, compareWidth),
                            ScalarExpression.Literal(right.value, compareWidth),
                            ScalarExpression.Literal(1, 1),
                            ScalarExpression.Literal(0, 1),
                            1
                        )
                    ) { error("No external input") } != 0L
                    if (selected) next = (instruction.destination as? Immediate)?.value ?: error("Indirect condition")
                    else tables.singleOrNull { it.guard == site }?.let { table ->
                        val index = number(table.index)
                        require(index >= 0 && index < table.targets.size)
                        // The table resolver proves the entire load/add/jump chain, including ingress and bounds.
                        // Forget both scratch registers before jumping directly to the validated selected case.
                        val jumpIndex = flow.instructions.indexOfFirst { it.offset == table.jump }
                        val add = flow.instructions.take(jumpIndex)
                            .last { it.operation !in listOf(Operation.NOP, Operation.ENDBR) }
                        val scratch = add.source as? Register ?: error("Missing switch base register")
                        registers.remove(scratch.number)
                        registers.remove((add.destination as Register).number)
                        next = table.targets[index.toInt()]
                        comparison = null
                    }
                }

                Operation.JMP -> next =
                    (instruction.destination as? Immediate)?.value ?: error("Unverified switch path")

                Operation.RET -> {
                    require(
                        top() == 0L && stack.isEmpty() &&
                                listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Saved(it) }) {
                        "Conversion fails to restore its native frame"
                    }
                    return number(Register(0, 4)).toInt()
                }

                Operation.NOP, Operation.ENDBR -> Unit
                else -> error("Unsupported integer conversion operation: ${instruction.operation}")
            }
            require(next in flow.body)
            if (tables.none { it.guard == site && next in it.targets })
                require(next in flow.successors.getValue(site))
            site = next
        }
    }
}
