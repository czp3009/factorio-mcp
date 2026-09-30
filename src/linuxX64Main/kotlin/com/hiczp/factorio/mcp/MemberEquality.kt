package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Named inline equality against an original receiver member; object extent is validated by the caller. */
internal data class MemberEquality(val offset: Long, val value: Int) {
    companion object {
        fun analyze(bytes: BinaryView, start: Long, end: Long, width: Int): MemberEquality {
            require(width in listOf(1, 4))
            require(bytes.size in 1..4096 && start >= 0 && end > start && end <= bytes.size)
            val flow = X64ControlFlow(X64Instructions(bytes).all(4096))
            var instructions = flow.instructions.filter { it.offset in start until end }
            require(
                instructions.isNotEmpty() && instructions.first().offset == start &&
                    instructions.last().let { it.offset + it.size } == end &&
                    instructions.all { it.offset in flow.reachable })
            // Optimized DWARF may attribute callee-save pushes to the first inline expression.
            // Admit only a straight entry prologue; arbitrary pushes inside a body are not an accessor.
            if (instructions.first().operation == Operation.PUSH) {
                val prefix = instructions.takeWhile { it.operation == Operation.PUSH }
                require(prefix.size < instructions.size)
                val comparisonOffset = instructions[prefix.size].offset
                require(flow.instructions.takeWhile { it.offset < comparisonOffset }.all {
                    when (it.operation) {
                        Operation.NOP, Operation.ENDBR -> true
                        Operation.PUSH -> it.destination is Register && it.destination.width == 8 &&
                                it.destination.number != 4

                        Operation.MOV -> it.destination == Register(5, 8) && it.source == Register(4, 8)
                        else -> false
                    }
                }) { "Inline equality contains non-prologue stack operations" }
                instructions = instructions.drop(prefix.size)
            }
            val comparison = instructions.first()
            require(comparison.operation == Operation.CMP)
            val memory = comparison.destination as? Memory ?: error("Member predicate does not compare a member")
            val value = (comparison.source as? Immediate)?.value ?: error("Member predicate has no enum constant")
            require(memory.width == width && value in Int.MIN_VALUE..Int.MAX_VALUE)
            val field = SysVArgumentFlow(flow).memory(comparison.offset, memory)
                ?: error("Member predicate lost receiver provenance")
            require(field.reference.argument == 7 && field.width == width)
            // This is an analysis bound, not proof of a concrete object's extent.
            require(field.reference.offset in 0..16 * 1024 * 1024 - width)
            // An inline range may contain the compare alone, with its consuming branch outside the range.
            // If the compiler includes materialization, it must be equality and must not modify the object.
            require(instructions.size in 1..2) { "Equality range contains ${instructions.size} instructions" }
            if (instructions.size == 2) {
                val materialize = instructions[1]
                require(materialize.operation == Operation.SET && materialize.condition == 4) {
                    "Equality range ends with ${materialize.operation}, condition ${materialize.condition}"
                }
                val destination = materialize.destination
                require(
                    destination !is Memory || SysVArgumentFlow(flow, includeStack = true)
                        .memory(materialize.offset, destination)?.reference?.argument == 4
                )
            }
            return MemberEquality(field.reference.offset, value.toInt())
        }
    }
}
