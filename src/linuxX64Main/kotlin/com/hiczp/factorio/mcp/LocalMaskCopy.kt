package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A known local word's guarded copy into a native event. Input/output identities are supplied independently. */
internal object LocalMaskCopy {
    fun verify(
        flow: X64ControlFlow, source: Long, sink: Long, masks: Set<Int>, output: Long,
        extent: Int, argument: Int = 6
    ) {
        require(masks.isNotEmpty() && masks.all { it in 1..65535 })
        require(extent in 2..4096 && output in 0..extent - 2L)
        val instruction = flow.body.getValue(source)
        val input = instruction.destination as? Register ?: error("Mask source is not a register load")
        require(
            instruction.operation == Operation.MOVZX && input.width == 4 &&
                    (instruction.source as? Memory)?.width == 2
        )
        for (mask in masks) {
            val edge = branch(flow, source, input, mask)
            val copies = LocalFieldCopies(flow, mapOf(edge)).fromRead(source, sink, argument)
                .filter { it.offset in 0..extent - it.width.toLong() }
            require(copies == listOf(LocalFieldCopies.Field(output, 2))) {
                "Native mask is changed, omitted or copied to another event field: $copies"
            }
        }
    }

    private fun branch(flow: X64ControlFlow, source: Long, input: Register, mask: Int): Pair<Long, Long> {
        val registers = MutableList<Long?>(16) { null }
        registers[input.number] = mask.toLong()
        var compare: Pair<Long, Long>? = null
        var position = source + flow.body.getValue(source).size
        var previous = source
        repeat(32) {
            require(flow.predecessors[position] == setOf(previous)) { "Mask condition has another entry edge" }
            val instruction = flow.body.getValue(position)
            fun scalar(operand: X64Instructions.Operand?, width: Int): Long? {
                require(width in listOf(1, 2, 4))
                val value = when (operand) {
                    is Immediate -> operand.value
                    is Register -> {
                        require(operand.number in 0..15)
                        registers[operand.number]
                    }

                    else -> null
                }
                return value?.and((1L shl (width * 8)) - 1)
            }
            when (instruction.operation) {
                Operation.MOV -> {
                    val target = instruction.destination as? Register ?: error("Mask prefix writes memory")
                    require(target.number in 0..15 && target.width == 4)
                    registers[target.number] = scalar(instruction.source, 4)
                }

                Operation.CMP -> {
                    val left = instruction.destination as? Register ?: error("Mask condition reads unrelated memory")
                    val first = scalar(left, left.width) ?: error("Mask condition has an unknown left operand")
                    val second =
                        scalar(instruction.source, left.width) ?: error("Mask condition has an unknown right operand")
                    compare = first to second
                }

                Operation.JCC -> {
                    require(instruction.condition in listOf(4, 5))
                    val (left, right) = checkNotNull(compare) { "Mask branch lacks an established condition" }
                    val taken = (left == right) == (instruction.condition == 4)
                    val next = if (taken) (instruction.destination as? Immediate)?.value
                        ?: error("Indirect mask branch") else position + instruction.size
                    require(next in flow.successors.getValue(position))
                    return position to next
                }

                Operation.NOP, Operation.ENDBR -> Unit
                else -> error("Unsupported mask condition operation ${instruction.operation}")
            }
            previous = position
            position += instruction.size
        }
        error("Mask condition exceeds its bound")
    }
}
