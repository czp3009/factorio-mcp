package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Named inline equality against an original receiver member; object extent is validated by the
 * caller.
 */
internal data class MemberEquality(val offset: Long, val value: Int) {
    companion object {
        data class Located(val member: MemberEquality, val instruction: Long)

        fun analyze(bytes: BinaryView, start: Long, end: Long, width: Int): MemberEquality =
            locate(bytes, start, end, width).member

        fun locate(bytes: BinaryView, start: Long, end: Long, width: Int): Located {
            require(width in listOf(1, 4))
            require(bytes.size in 1..4096 && start >= 0 && end > start && end <= bytes.size)
            val flow = X64ControlFlow(X64Instructions(bytes).all(4096))
            var instructions = flow.instructions.filter { it.offset in start until end }
            require(
                instructions.isNotEmpty() &&
                    instructions.first().offset == start &&
                    instructions.last().let { it.offset + it.size } == end &&
                    instructions.all { it.offset in flow.reachable }
            )
            // Optimized DWARF may attribute callee-save pushes to the first inline expression.
            // Admit only a straight entry prologue; arbitrary pushes inside a body are not an
            // accessor.
            if (instructions.first().operation == Operation.PUSH) {
                val prefix = instructions.takeWhile { it.operation == Operation.PUSH }
                require(prefix.size < instructions.size)
                val comparisonOffset = instructions[prefix.size].offset
                require(
                    flow.instructions
                        .takeWhile { it.offset < comparisonOffset }
                        .all {
                            when (it.operation) {
                                Operation.NOP,
                                Operation.ENDBR -> true
                                Operation.PUSH ->
                                    it.destination is Register &&
                                        it.destination.width == 8 &&
                                        it.destination.number != 4

                                Operation.MOV ->
                                    it.destination == Register(5, 8) && it.source == Register(4, 8)
                                else -> false
                            }
                        }
                ) {
                    "Inline equality contains non-prologue stack operations"
                }
                instructions = instructions.drop(prefix.size)
            }
            val comparison = instructions.first()
            require(comparison.operation == Operation.CMP)
            val value =
                (comparison.source as? Immediate)?.value
                    ?: error("Member predicate has no enum constant")
            require(value in Int.MIN_VALUE..Int.MAX_VALUE)
            val field =
                when (val operand = comparison.destination) {
                    is Memory ->
                        SysVArgumentFlow(flow).memory(comparison.offset, operand)
                            ?: error("Member predicate lost receiver provenance")
                    is Register -> {
                        var scalar =
                            ScalarExpression(flow, mapOf(7 to 16L * 1024 * 1024))
                                .before(comparison.offset, operand)
                        while (scalar is ScalarExpression.Narrow) {
                            require(scalar.width >= width)
                            scalar = scalar.value
                        }
                        (scalar as? ScalarExpression.Input)?.field
                            ?: error("Member predicate changes the loaded scalar")
                    }
                    else -> error("Member predicate does not compare a member")
                }
            require(field.reference.argument == 7 && field.width == width)
            // This is an analysis bound, not proof of a concrete object's extent.
            require(field.reference.offset in 0..16 * 1024 * 1024 - width)
            // An inline range may contain the compare alone, with its consuming branch outside the
            // range.
            // If the compiler includes materialization, it must be equality and must not modify the
            // object.
            val frame by lazy { SysVLocalArgument(flow) }
            for (instruction in instructions.drop(1)) {
                when (instruction.operation) {
                    Operation.NOP,
                    Operation.ENDBR -> continue
                    Operation.SET -> require(instruction.condition == 4)
                    Operation.MOV,
                    Operation.SCALAR_MOV,
                    Operation.VECTOR_MOV,
                    Operation.LEA -> Unit
                    else ->
                        error(
                            "Inline equality changes flags or control flow: ${instruction.operation}"
                        )
                }
                when (val destination = instruction.destination) {
                    is Register -> require(destination.number !in listOf(4, 5))
                    is Memory -> {
                        require(instruction.operation != Operation.LEA)
                        val slot =
                            frame.address(instruction.offset, destination)
                                ?: error("Inline equality writes outside its private frame")
                        require(
                            slot >= checkNotNull(frame.registers(instruction.offset)[4]) &&
                                slot <= -destination.width
                        )
                    }
                    else -> error("Inline equality has an unsupported destination")
                }
            }
            return Located(MemberEquality(field.reference.offset, value.toInt()), comparison.offset)
        }
    }
}
