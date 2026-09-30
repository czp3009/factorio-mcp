package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Offline projection of selected scalar stores. Unknown data may flow, but may never select a branch or output. */
internal object ScalarOutputSlice {
    fun evaluate(
        flow: X64ControlFlow,
        start: Long,
        input: Register,
        value: Int,
        call: Long,
        convert: (Int) -> Int,
        outputs: Set<Long>,
    ): Map<Long, Long> {
        require(
            start in flow.reachable && input.number in 0..15 && input.width == 4 &&
                    flow.body[call]?.operation == Operation.CALL && outputs.isNotEmpty() && outputs.size <= 16
        )
        // No private address can escape to a call or external store. Keeping scalar spills across
        // the selected pure call is therefore independent of arbitrary native memory contents.
        ConstructorValues(flow, emptyMap())
        val frame = SysVLocalArgument(flow)
        val registers = MutableList(32) { MutableList<Int?>(16) { null } }
        val locals = mutableMapOf<Long, Int>()
        val result = mutableMapOf<Long, Long>()
        val path = mutableSetOf<Long>()
        var comparison: Pair<Long, Long>? = null
        var compareWidth = 0
        var converted = false
        var site = start
        fun bytes(number: Long, width: Int) = List(width) { ((number ushr (it * 8)) and 255).toInt() }
        fun location(memory: Memory): Long? = frame.address(site, memory)?.also {
            require(it >= checkNotNull(frame.registers(site)[4]) && it <= -memory.width)
        }

        fun read(operand: Operand?, width: Int): List<Int?> = when (operand) {
            is Immediate -> bytes(operand.value, width)
            is Register -> registers[operand.number].take(operand.width)
            is Memory -> location(operand).let { slot -> List(operand.width) { if (slot == null) null else locals[slot + it] } }
            else -> error("Missing scalar slice operand")
        }

        fun number(operand: Operand?, width: Int): Long? {
            require(width in listOf(1, 2, 4, 8))
            val data = read(operand, width)
            if (data.size < width || data.take(width).any { it == null }) return null
            return (0 until width).fold(0L) { sum, index -> sum or (data[index]!!.toLong() shl (index * 8)) }
        }

        fun write(operand: Operand?, data: List<Int?>) {
            when (operand) {
                is Register -> {
                    for (index in 0 until operand.width) registers[operand.number][index] = data.getOrNull(index)
                    if (operand.number < 16 && operand.width == 4)
                        for (index in 4..7) registers[operand.number][index] = 0
                    if (operand.number >= 16)
                        for (index in operand.width until 16) registers[operand.number][index] = null
                }

                is Memory -> location(operand)?.let { slot ->
                    repeat(operand.width) { index ->
                        locals.remove(slot + index)
                        data.getOrNull(index)?.let { locals[slot + index] = it }
                    }
                }

                else -> error("Unsupported scalar slice destination")
            }
        }

        fun condition(code: Int): Boolean? {
            val (left, right) = comparison ?: return null
            return ScalarExpression.evaluate(
                ScalarExpression.Select(
                    code,
                    ScalarExpression.Literal(left, compareWidth), ScalarExpression.Literal(right, compareWidth),
                    ScalarExpression.Literal(1, 1), ScalarExpression.Literal(0, 1), 1
                )
            ) { error("No external input") } != 0L
        }
        write(input, bytes(value.toLong(), 4))
        while (true) {
            require(path.size < 256 && path.add(site)) { "Scalar output slice loops or exceeds its bound" }
            val instruction = flow.body.getValue(site)
            val target = instruction.destination
            val width = when (target) {
                is Register -> target.width; is Memory -> target.width; else -> 0
            }
            var next = site + instruction.size
            if (site in outputs) {
                require(
                    instruction.operation == Operation.MOV && target is Memory && target.width == 4 &&
                            location(target) == null && converted
                )
                result[site] = checkNotNull(number(instruction.source, 4)) { "Selected output depends on unknown data" }
                if (result.keys == outputs) return result
            }
            when (instruction.operation) {
                Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV -> write(
                    target,
                    read(instruction.source, width)
                )

                Operation.MOVZX -> {
                    val source = read(instruction.source, width)
                    write(target, source + List((width - source.size).coerceAtLeast(0)) { 0 })
                }

                Operation.LEA -> {
                    val source = instruction.source as Memory
                    require(!source.relative && source.scale in listOf(1, 2, 4, 8))
                    val base = source.base?.let { number(Register(it, 8), 8) } ?: if (source.base == null) 0L else null
                    val index =
                        source.index?.let { number(Register(it, 8), 8) } ?: if (source.index == null) 0L else null
                    write(
                        target,
                        if (base != null && index != null) bytes(
                            base + index * source.scale + source.displacement,
                            width
                        )
                        else emptyList()
                    )
                }

                Operation.ADD, Operation.SUB, Operation.XOR, Operation.AND, Operation.OR -> {
                    val left = number(target, width)
                    val right = number(instruction.source, width)
                    val computed = if (instruction.operation == Operation.XOR && target == instruction.source) 0L
                    else if (left == null || right == null) null else when (instruction.operation) {
                        Operation.ADD -> left + right
                        Operation.SUB -> left - right
                        Operation.XOR -> left xor right
                        Operation.AND -> left and right
                        else -> left or right
                    }
                    write(target, computed?.let { bytes(it, width) }.orEmpty())
                    comparison = null
                }

                Operation.CMP, Operation.TEST -> {
                    require(width in listOf(1, 2, 4))
                    val left = number(target, width)
                    val right = number(instruction.source, width)
                    comparison = if (left == null || right == null) null
                    else if (instruction.operation == Operation.TEST) (left and right) to 0L else left to right
                    compareWidth = width
                }

                Operation.CMOV -> {
                    val old = read(target, width)
                    val replacement = read(instruction.source, width)
                    write(
                        target, when (condition(checkNotNull(instruction.condition))) {
                        true -> replacement
                        false -> old
                        null -> old.mapIndexed { index, byte -> byte.takeIf { it == replacement.getOrNull(index) } }
                    })
                }

                Operation.CALL -> {
                    require(site == call && !converted) { "Scalar slice reaches an unverified call" }
                    val argument = checkNotNull(number(Register(7, 4), 4))
                    require(argument == value.toLong() and 0xffffffffL) { "Conversion input changed before its call" }
                    val output = convert(argument.toInt())
                    for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31))
                        registers[register].fill(null)
                    write(Register(0, 4), bytes(output.toLong(), 4))
                    comparison = null
                    converted = true
                }

                Operation.JCC -> if (checkNotNull(condition(checkNotNull(instruction.condition))) {
                        "Scalar slice branch depends on unknown flags"
                    }) next = (target as? Immediate)?.value ?: error("Indirect scalar condition")

                Operation.JMP -> next =
                    (target as? Immediate)?.value ?: error("Scalar slice reaches an indirect branch")

                Operation.NOP, Operation.ENDBR -> Unit
                else -> error("Unsupported scalar output operation: ${instruction.operation}")
            }
            require(next in flow.successors.getValue(site)) { "Scalar slice leaves its verified function" }
            site = next
        }
    }
}
