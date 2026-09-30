package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Bounded integer value slices. Input reads remain separate observations; object lifetime is a caller obligation. */
internal class ScalarExpression(
    private val flow: X64ControlFlow, private val extents: Map<Int, Long> = emptyMap(),
    private val arguments: SysVArgumentFlow = SysVArgumentFlow(flow)
) {
    sealed interface Value {
        val width: Int
    }

    data class Literal(val value: Long, override val width: Int) : Value
    data class Input(val site: Long, val field: SysVArgumentFlow.Read, override val width: Int) : Value
    data class Narrow(val value: Value, override val width: Int) : Value
    data class Binary(val operation: Operation, val left: Value, val right: Value, override val width: Int) : Value
    data class Select(
        val condition: Int, val left: Value, val right: Value, val yes: Value, val no: Value,
        override val width: Int
    ) : Value

    private val values = mutableMapOf<Pair<Long, Register>, Value>()
    private val active = mutableSetOf<Pair<Long, Register>>()
    private var work = 0

    init {
        require(extents.all { (argument, extent) ->
            argument in listOf(7, 6, 2, 1, 8, 9) && extent in 1..4096
        })
    }

    fun before(site: Long, register: Register): Value {
        require(site in flow.reachable && register.number in 0..15 && register.width in listOf(1, 2, 4))
        val key = site to register
        values[key]?.let { return it }
        require(++work <= 2048 && active.size < 128 && active.add(key)) { "Scalar value slice is cyclic or exceeds bounds" }
        try {
            val instruction = definition(site, register.number)
            val target = instruction.destination as? Register ?: error("Scalar definition is not a register write")
            require(target.number == register.number && target.width in listOf(1, 2, 4)) {
                "Scalar value requires an unsupported register definition"
            }
            val width = target.width
            val result = when (instruction.operation) {
                Operation.MOV, Operation.MOVZX -> operand(instruction.offset, instruction.source, width)
                Operation.LEA -> {
                    val address = instruction.source as? Memory ?: error("Scalar LEA has no address")
                    require(width == 4 && !address.relative && address.scale in listOf(1, 2, 4, 8))
                    var sum: Value = Literal(address.displacement, width)
                    address.base?.let {
                        sum = Binary(Operation.ADD, sum, before(instruction.offset, Register(it, width)), width)
                    }
                    address.index?.let {
                        val index = before(instruction.offset, Register(it, width))
                        val shift = when (address.scale) {
                            1 -> 0; 2 -> 1; 4 -> 2; else -> 3
                        }
                        sum = Binary(
                            Operation.ADD, sum,
                            Binary(Operation.SHL, index, Literal(shift.toLong(), width), width), width
                        )
                    }
                    sum
                }

                Operation.XOR -> if (instruction.source == target) Literal(0, width)
                else Binary(
                    Operation.XOR, before(instruction.offset, target),
                    operand(instruction.offset, instruction.source, width), width
                )

                Operation.ADD, Operation.SUB, Operation.AND, Operation.OR, Operation.SHL, Operation.SHR, Operation.SAR,
                Operation.ROL, Operation.ROR ->
                    Binary(
                        instruction.operation, before(instruction.offset, target),
                        operand(instruction.offset, instruction.source, width), width
                    )

                Operation.INC, Operation.DEC -> Binary(
                    if (instruction.operation == Operation.INC) Operation.ADD else Operation.SUB,
                    before(instruction.offset, target), Literal(1, width), width
                )

                Operation.NOT -> Binary(Operation.XOR, before(instruction.offset, target), Literal(-1, width), width)
                Operation.CMOV -> conditional(
                    instruction,
                    operand(instruction.offset, instruction.source, width), before(instruction.offset, target), width
                )

                Operation.SET -> {
                    require(width == 1)
                    conditional(instruction, Literal(1, width), Literal(0, width), width)
                }

                else -> error("Unsupported scalar definition: ${instruction.operation}")
            }
            // Byte/word writes preserve the other bits. Recover them from the prior full value,
            // even for SETcc; a flag result alone never proves that the upper bits are zero.
            val combined = if (width < register.width) Binary(
                Operation.OR,
                Binary(
                    Operation.AND, before(instruction.offset, register),
                    Literal(-1L shl (width * 8), register.width), register.width
                ),
                Narrow(result, width), register.width
            ) else result
            return Narrow(combined, register.width).also { values[key] = it }
        } finally {
            active.remove(key)
        }
    }

    fun definition(site: Long, register: Int): Instruction {
        require(site in flow.reachable && register in 0..15)
        return reaching(site) { defines(it, register) }
    }

    fun branch(site: Long): Value {
        val instruction = flow.body.getValue(site)
        require(site in flow.reachable && instruction.operation == Operation.JCC)
        return conditional(instruction, Literal(1, 1), Literal(0, 1), 1)
    }

    private fun conditional(instruction: Instruction, yes: Value, no: Value, width: Int): Value {
        val comparison = reaching(instruction.offset, ::changesFlags)
        require(comparison.operation in listOf(Operation.CMP, Operation.TEST)) {
            "Conditional scalar value lacks an integer comparison or test"
        }
        val compareWidth = when (val left = comparison.destination) {
            is Register -> left.width
            is Memory -> left.width
            else -> error("Invalid scalar comparison")
        }
        require(compareWidth in listOf(1, 2, 4))
        val condition = checkNotNull(instruction.condition)
        require(condition in setOf(2, 3, 4, 5, 6, 7, 12, 13, 14, 15))
        val left = operand(comparison.offset, comparison.destination, compareWidth)
        val right = operand(comparison.offset, comparison.source, compareWidth)
        val test = comparison.operation == Operation.TEST
        // TEST sets CF/OF to zero; comparing the masked result with zero produces the same
        // supported equality, carry, sign and overflow predicates.
        return Select(
            condition, if (test) Binary(Operation.AND, left, right, compareWidth) else left,
            if (test) Literal(0, compareWidth) else right, yes, no, width
        )
    }

    private fun operand(site: Long, operand: Operand?, width: Int): Value = when (operand) {
        is Immediate -> Literal(operand.value, width)
        is Register -> before(site, operand)
        is Memory -> {
            require(operand.width in listOf(1, 2, 4))
            val read = arguments.memory(site, operand)
                ?: error("Scalar input at $site has no original argument provenance: $operand")
            val extent = extents[read.reference.argument] ?: error("Scalar input uses an unbounded object")
            require(read.reference.offset >= 0 && read.reference.offset <= extent - read.width)
            Input(site, read, operand.width)
        }

        else -> error("Unsupported scalar input")
    }

    private fun reaching(site: Long, matches: (Instruction) -> Boolean): Instruction {
        val pending = ArrayDeque<Long>()
        pending.addAll(flow.predecessors[site].orEmpty())
        val visited = mutableSetOf<Long>()
        val found = mutableSetOf<Long>()
        while (pending.isNotEmpty()) {
            val position = pending.removeFirst()
            if (!visited.add(position)) continue
            require(visited.size <= 4096) { "Scalar reaching-definition search exceeds bounds" }
            val instruction = flow.body.getValue(position)
            if (matches(instruction)) found += position
            else {
                require(position != 0L) { "Scalar value depends on an unproven entry register" }
                pending.addAll(flow.predecessors[position].orEmpty())
            }
        }
        return flow.body[found.singleOrNull()] ?: error("Scalar value has ambiguous reaching definitions")
    }

    private fun defines(instruction: Instruction, register: Int): Boolean {
        if (instruction.operation == Operation.BYTE_COMPARE_EXCHANGE) return register == 0
        if (instruction.operation == Operation.CALL) return register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)
        if (instruction.operation in listOf(
                Operation.CMP, Operation.TEST, Operation.BIT_TEST, Operation.SCALAR_COMPARE, Operation.NOP,
                Operation.ENDBR, Operation.JCC, Operation.JMP, Operation.RET, Operation.PUSH
            )
        ) return false
        return (instruction.destination as? Register)?.number == register ||
                instruction.operation == Operation.XCHG && (instruction.source as? Register)?.number == register
    }

    private fun changesFlags(instruction: Instruction): Boolean = instruction.operation !in listOf(
        Operation.MOV,
        Operation.MOVZX,
        Operation.MOVSX,
        Operation.LEA,
        Operation.PUSH,
        Operation.POP,
        Operation.CMOV,
        Operation.SET,
        Operation.XCHG,
        Operation.NOP,
        Operation.ENDBR,
        Operation.JMP,
        Operation.JCC,
        Operation.VECTOR_MOV,
        Operation.SCALAR_MOV,
        Operation.VECTOR_XOR,
        Operation.VECTOR_AND,
        Operation.VECTOR_AND_NOT,
        Operation.VECTOR_OR,
        Operation.VECTOR_ADD_DWORDS,
        Operation.VECTOR_EQUAL_DWORDS,
        Operation.VECTOR_GREATER_DWORDS,
        Operation.VECTOR_SHIFT_LEFT_DWORDS,
        Operation.VECTOR_SHIFT_RIGHT_DWORDS
    )

    companion object {
        fun inputs(value: Value): Set<Input> {
            val result = mutableSetOf<Input>()
            val pending = ArrayDeque<Value>()
            pending.add(value)
            var work = 0
            while (pending.isNotEmpty()) {
                require(++work <= 8192) { "Scalar input traversal exceeds bounds" }
                when (val node = pending.removeFirst()) {
                    is Input -> result += node
                    is Literal -> Unit
                    is Narrow -> pending.add(node.value)
                    is Binary -> pending.addAll(listOf(node.left, node.right))
                    is Select -> pending.addAll(listOf(node.left, node.right, node.yes, node.no))
                }
            }
            return result
        }

        fun evaluate(value: Value, read: (Input) -> Long): Long {
            var work = 0
            fun unsigned(value: Long, width: Int): Long {
                require(width in listOf(1, 2, 4))
                return value and ((1L shl (width * 8)) - 1)
            }

            fun signed(value: Long, width: Int) = value shl (64 - width * 8) shr (64 - width * 8)
            fun evaluate(node: Value): Long {
                require(++work <= 8192) { "Scalar evaluation exceeds bounds" }
                val result = when (node) {
                    is Literal -> node.value
                    is Input -> read(node)
                    is Narrow -> evaluate(node.value)
                    is Binary -> {
                        val left = evaluate(node.left)
                        val right = evaluate(node.right)
                        when (node.operation) {
                            Operation.ADD -> left + right
                            Operation.SUB -> left - right
                            Operation.AND -> left and right
                            Operation.OR -> left or right
                            Operation.XOR -> left xor right
                            Operation.SHL -> left shl (right.toInt() and 31)
                            Operation.SHR -> left ushr (right.toInt() and 31)
                            Operation.SAR -> signed(left, node.width) shr (right.toInt() and 31)
                            Operation.ROL, Operation.ROR -> {
                                val bits = node.width * 8
                                val count = (right.toInt() and 31) % bits
                                if (count == 0) left else if (node.operation == Operation.ROL)
                                    (left shl count) or (left ushr (bits - count))
                                else (left ushr count) or (left shl (bits - count))
                            }

                            else -> error("Unsupported scalar expression")
                        }
                    }

                    is Select -> {
                        val left = evaluate(node.left)
                        val right = evaluate(node.right)
                        require(node.left.width == node.right.width)
                        val signedLeft = signed(left, node.left.width)
                        val signedRight = signed(right, node.right.width)
                        val selected = when (node.condition) {
                            2 -> left < right
                            3 -> left >= right
                            4 -> left == right
                            5 -> left != right
                            6 -> left <= right
                            7 -> left > right
                            12 -> signedLeft < signedRight
                            13 -> signedLeft >= signedRight
                            14 -> signedLeft <= signedRight
                            15 -> signedLeft > signedRight
                            else -> error("Unsupported scalar condition")
                        }
                        evaluate(if (selected) node.yes else node.no)
                    }
                }
                return unsigned(result, node.width)
            }
            return evaluate(value)
        }
    }
}
